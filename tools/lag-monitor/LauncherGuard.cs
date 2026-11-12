using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace CoopLagMonitor {
    // Owns only handles explicitly registered by this capture. No process-name lookup or tree kill.
    public sealed class LauncherGuard : IDisposable {
        const uint SameAccess = 2;
        const uint WaitObject0 = 0;
        const uint WaitTimeout = 258;
        const uint CreateSuspended = 4;
        const uint CreateNoWindow = 0x08000000;
        const uint KillOnJobClose = 0x00002000;
        readonly object gate = new object();
        readonly List<Entry> entries = new List<Entry>();
        readonly List<string> failureMessages = new List<string>();
        readonly ManualResetEvent stopped = new ManualResetEvent(false);
        readonly Stopwatch clock = Stopwatch.StartNew();
        readonly Thread thread;
        readonly int deadline;
        IntPtr job;
        bool disposed;
        bool expired;

        sealed class Entry {
            public Process process;
            public IntPtr handle;
            public int pid;
            public string exe;
            public long creationTime;
            public bool jobOwned;
            public bool stopRequested;
            public bool exited;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct FileTime { public uint low; public uint high; }
        [StructLayout(LayoutKind.Sequential)]
        struct BasicJobLimits {
            public long processTime;
            public long jobTime;
            public uint flags;
            public UIntPtr minimumWorkingSet;
            public UIntPtr maximumWorkingSet;
            public uint activeProcessLimit;
            public UIntPtr affinity;
            public uint priorityClass;
            public uint schedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct IoCounters {
            public ulong readOperations, writeOperations, otherOperations;
            public ulong readBytes, writeBytes, otherBytes;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct ExtendedJobLimits {
            public BasicJobLimits basic;
            public IoCounters io;
            public UIntPtr processMemory, jobMemory, peakProcessMemory, peakJobMemory;
        }
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct StartupInfo {
            public int size;
            public string reserved, desktop, title;
            public uint x, y, xSize, ySize, xCharacters, yCharacters, fillAttribute, flags;
            public ushort showWindow, reservedBytes;
            public IntPtr reservedPointer, standardInput, standardOutput, standardError;
        }
        [StructLayout(LayoutKind.Sequential)]
        struct ProcessInformation {
            public IntPtr process, thread;
            public uint pid, threadId;
        }
        [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool SetInformationJobObject(IntPtr job, int informationClass, ref ExtendedJobLimits limits, uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateJobObject(IntPtr job, uint exitCode);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool CreateProcessW(string application, StringBuilder commandLine, IntPtr processAttributes,
            IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory,
            ref StartupInfo startup, out ProcessInformation information);
        [DllImport("kernel32.dll", SetLastError = true)] static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool DuplicateHandle(IntPtr sourceProcess, IntPtr sourceHandle, IntPtr targetProcess,
            out IntPtr targetHandle, uint access, bool inherit, uint options);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool CloseHandle(IntPtr handle);
        [DllImport("kernel32.dll", SetLastError = true)] static extern uint GetProcessId(IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetProcessTimes(IntPtr process, out FileTime creation, out FileTime exit,
            out FileTime kernel, out FileTime user);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern bool QueryFullProcessImageNameW(IntPtr process, uint flags, StringBuilder name, ref uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool TerminateProcess(IntPtr process, uint exitCode);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

        public LauncherGuard(int deadlineMs) {
            if (deadlineMs <= 0 || deadlineMs > 3600000)
                throw new ArgumentOutOfRangeException("deadlineMs");
            deadline = deadlineMs;
            job = CreateJobObjectW(IntPtr.Zero, null);
            if (job == IntPtr.Zero) throw NativeError("Create capture job");
            var limits = new ExtendedJobLimits();
            limits.basic.flags = KillOnJobClose;
            if (!SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(typeof(ExtendedJobLimits)))) {
                var error = NativeError("Set capture job kill-on-close limit");
                CloseHandle(job); job = IntPtr.Zero;
                throw error;
            }
            thread = new Thread(Run) { IsBackground = true, Name = "CoopLagLauncherGuard" };
            try { thread.Start(); }
            catch { CloseHandle(job); job = IntPtr.Zero; throw; }
        }

        public bool timedOut { get { lock (gate) return expired; } }
        public string[] errors { get { lock (gate) return failureMessages.ToArray(); } }
        public LauncherIdentity[] owned {
            get {
                lock (gate) {
                    var result = new List<LauncherIdentity>();
                    foreach (Entry entry in entries) {
                        if (entry.handle != IntPtr.Zero && WaitForSingleObject(entry.handle, 0) == WaitObject0)
                            entry.exited = true;
                        result.Add(new LauncherIdentity {
                            pid = entry.pid, exe = entry.exe,
                            startedUtc = DateTime.FromFileTimeUtc(entry.creationTime).ToString("o"),
                            jobOwned = entry.jobOwned, stopRequested = entry.stopRequested, exited = entry.exited
                        });
                    }
                    return result.ToArray();
                }
            }
        }

        public Process StartOwned(string exe, string cwd, string arguments, bool visible) {
            if (String.IsNullOrWhiteSpace(exe)) throw new ArgumentException("Executable is required", "exe");
            if (String.IsNullOrWhiteSpace(cwd)) throw new ArgumentException("Working directory is required", "cwd");
            string expected = Path.GetFullPath(exe);
            string directory = Path.GetFullPath(cwd);
            if (expected.IndexOf('"') >= 0) throw new ArgumentException("Executable path contains a quote", "exe");
            var command = new StringBuilder("\"" + expected + "\"" + (String.IsNullOrWhiteSpace(arguments) ? "" : " " + arguments));
            IntPtr ownedJob;
            lock (gate) {
                if (disposed) throw new ObjectDisposedException("LauncherGuard");
                if (expired || clock.ElapsedMilliseconds >= deadline) {
                    expired = true;
                    throw new TimeoutException("Launcher capture deadline has expired");
                }
                IntPtr current = GetCurrentProcess();
                if (!DuplicateHandle(current, job, current, out ownedJob, 0, false, SameAccess))
                    throw NativeError("Retain capture job during launch");
            }
            ProcessInformation created = new ProcessInformation();
            Process process = null;
            bool createdNative = false;
            bool resumed = false;
            try {
                var startup = new StartupInfo();
                startup.size = Marshal.SizeOf(typeof(StartupInfo));
                startup.flags = 1; // STARTF_USESHOWWINDOW
                startup.showWindow = (ushort)(visible ? 1 : 0);
                // Process creation does not hold the watchdog lock; an existing launcher can still time out.
                if (!CreateProcessW(expected, command, IntPtr.Zero, IntPtr.Zero, false,
                        CreateSuspended | (visible ? 0u : CreateNoWindow), IntPtr.Zero, directory,
                        ref startup, out created)) throw NativeError("Create suspended capture launcher");
                createdNative = true;
                // The child cannot spawn a JVM until it belongs to our non-breakaway job.
                if (!AssignProcessToJobObject(ownedJob, created.process)) throw NativeError("Assign suspended launcher to capture job");
                process = Process.GetProcessById(checked((int)created.pid));
                if (CreationTime(created.process) != process.StartTime.ToUniversalTime().ToFileTimeUtc())
                    throw new InvalidOperationException("Created launcher identity changed before registration");
                lock (gate) {
                    if (disposed) throw new ObjectDisposedException("LauncherGuard");
                    if (expired || clock.ElapsedMilliseconds >= deadline) {
                        expired = true;
                        throw new TimeoutException("Launcher capture deadline has expired");
                    }
                    Track(process, expected);
                    uint previous = ResumeThread(created.thread);
                    if (previous == UInt32.MaxValue) throw NativeError("Resume capture launcher");
                    if (previous != 1) throw new InvalidOperationException("Unexpected launcher thread suspend count: " + previous);
                    resumed = true;
                }
                return process;
            } finally {
                if (createdNative) {
                    if (!resumed) {
                        if (WaitForSingleObject(created.process, 0) != WaitObject0 && !TerminateProcess(created.process, 1)) {
                            string error = NativeError("Terminate failed launcher").Message;
                            lock (gate) failureMessages.Add("Could not terminate failed suspended launcher " + created.pid + ": " + error);
                        }
                        if (WaitForSingleObject(created.process, 3000) != WaitObject0)
                            lock (gate) failureMessages.Add("Failed suspended launcher did not exit: " + created.pid);
                        if (process != null) process.Dispose();
                    }
                    CloseHandle(created.thread);
                    CloseHandle(created.process);
                }
                CloseHandle(ownedJob);
            }
        }

        public void Track(Process process, string expectedExe) {
            if (process == null) throw new ArgumentNullException("process");
            if (String.IsNullOrWhiteSpace(expectedExe)) throw new ArgumentException("Expected executable is required", "expectedExe");
            string expected = Path.GetFullPath(expectedExe);
            IntPtr retained = IntPtr.Zero;
            lock (gate) {
                if (disposed) throw new ObjectDisposedException("LauncherGuard");
                try {
                    IntPtr current = GetCurrentProcess();
                    if (!DuplicateHandle(current, process.Handle, current, out retained, 0, false, SameAccess))
                        throw NativeError("Duplicate launcher process handle");
                    int pid = checked((int)GetProcessId(retained));
                    if (pid <= 0 || pid != process.Id)
                        throw new InvalidOperationException("Launcher handle PID does not match supplied Process");
                    string actual = ImagePath(retained);
                    long creation = CreationTime(retained);
                    if (!String.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidOperationException("Launcher executable mismatch: expected " + expected + ", got " + actual);
                    if (creation != process.StartTime.ToUniversalTime().ToFileTimeUtc())
                        throw new InvalidOperationException("Launcher handle creation time does not match supplied Process");
                    bool jobOwned;
                    if (!IsProcessInJob(retained, job, out jobOwned)) throw NativeError("Check capture job membership");
                    foreach (Entry existing in entries) {
                        if (existing.pid == pid && existing.creationTime == creation &&
                                String.Equals(existing.exe, actual, StringComparison.OrdinalIgnoreCase)) return;
                    }
                    if (entries.Count >= 16) throw new InvalidOperationException("Launcher ownership limit exceeded");
                    var entry = new Entry { process = process, handle = retained, pid = pid, exe = actual, creationTime = creation, jobOwned = jobOwned };
                    entries.Add(entry);
                    retained = IntPtr.Zero;
                    // A launch racing the deadline is still registered and closed through its original handle.
                    if (expired || clock.ElapsedMilliseconds >= deadline) {
                        expired = true;
                        StopEntry(entry);
                        throw new TimeoutException("Launcher capture deadline has expired");
                    }
                } finally {
                    if (retained != IntPtr.Zero) CloseHandle(retained);
                }
            }
        }

        public void Stop(int pid) {
            lock (gate) {
                if (disposed) throw new ObjectDisposedException("LauncherGuard");
                bool found = false;
                foreach (Entry entry in entries) {
                    if (entry.pid != pid) continue;
                    found = true;
                    StopEntry(entry);
                }
                if (!found) throw new ArgumentException("PID is not owned by this capture: " + pid, "pid");
            }
        }

        public void StopAll() {
            lock (gate) {
                if (disposed) return;
                foreach (Entry entry in entries) {
                    if (entry.jobOwned && entry.handle != IntPtr.Zero && !entry.exited &&
                            WaitForSingleObject(entry.handle, 0) == WaitTimeout) entry.stopRequested = true;
                }
                bool jobTerminated = job != IntPtr.Zero && TerminateJobObject(job, 1);
                if (job != IntPtr.Zero && !jobTerminated)
                    failureMessages.Add("Terminate capture job: " + NativeError("Terminate capture job").Message);
                foreach (Entry entry in entries) {
                    // Job termination is asynchronous. Avoid racing a second terminate against its pending exit.
                    if (jobTerminated && entry.jobOwned && entry.handle != IntPtr.Zero && !entry.exited &&
                            WaitForSingleObject(entry.handle, 3000) == WaitObject0) entry.exited = true;
                    StopEntry(entry);
                }
            }
        }

        void StopEntry(Entry entry) {
            if (entry.handle == IntPtr.Zero || entry.exited) return;
            uint wait = WaitForSingleObject(entry.handle, 0);
            if (wait == WaitObject0) { entry.exited = true; return; }
            if (wait != WaitTimeout) { Record(entry, NativeError("Check launcher exit")); return; }
            try {
                if (GetProcessId(entry.handle) != entry.pid || CreationTime(entry.handle) != entry.creationTime ||
                        !String.Equals(ImagePath(entry.handle), entry.exe, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("Retained launcher identity changed; refusing termination");
                entry.stopRequested = true;
                if (!TerminateProcess(entry.handle, 1)) {
                    int error = Marshal.GetLastWin32Error();
                    if (WaitForSingleObject(entry.handle, 0) == WaitObject0) { entry.exited = true; return; }
                    throw new Win32Exception(error, "Terminate retained launcher handle");
                }
                wait = WaitForSingleObject(entry.handle, 3000);
                if (wait == WaitObject0) entry.exited = true;
                else if (wait == WaitTimeout) throw new InvalidOperationException("Launcher did not exit within 3000 ms of termination");
                else throw NativeError("Wait for launcher termination");
            } catch (Exception error) {
                if (WaitForSingleObject(entry.handle, 0) == WaitObject0) entry.exited = true;
                else Record(entry, error);
            }
        }

        void Run() {
            while (!stopped.WaitOne(100)) {
                if (clock.ElapsedMilliseconds < deadline) continue;
                lock (gate) { if (disposed) return; expired = true; }
                StopAll();
                return;
            }
        }

        void Record(Entry entry, Exception error) {
            failureMessages.Add("Owned launcher " + entry.pid + " (" + entry.exe + "): " + error.Message);
        }
        static Win32Exception NativeError(string action) { return new Win32Exception(Marshal.GetLastWin32Error(), action); }
        static long CreationTime(IntPtr handle) {
            FileTime creation, exit, kernel, user;
            if (!GetProcessTimes(handle, out creation, out exit, out kernel, out user)) throw NativeError("Read launcher creation time");
            return ((long)creation.high << 32) | creation.low;
        }
        static string ImagePath(IntPtr handle) {
            var path = new StringBuilder(32768);
            uint size = (uint)path.Capacity;
            if (!QueryFullProcessImageNameW(handle, 0, path, ref size)) throw NativeError("Read launcher executable path");
            return Path.GetFullPath(path.ToString());
        }

        public void Dispose() {
            lock (gate) { if (disposed) return; }
            stopped.Set();
            StopAll();
            if (Thread.CurrentThread != thread && !thread.Join(5000))
                lock (gate) { failureMessages.Add("Launcher guard thread did not stop within 5000 ms"); }
            lock (gate) {
                if (disposed) return;
                disposed = true;
                foreach (Entry entry in entries) {
                    if (entry.handle != IntPtr.Zero) { CloseHandle(entry.handle); entry.handle = IntPtr.Zero; }
                }
                if (job != IntPtr.Zero) { CloseHandle(job); job = IntPtr.Zero; }
                stopped.Dispose();
            }
        }
    }

    public sealed class LauncherIdentity {
        public int pid { get; set; }
        public string exe { get; set; }
        public string startedUtc { get; set; }
        public bool jobOwned { get; set; }
        public bool stopRequested { get; set; }
        public bool exited { get; set; }
    }
}
