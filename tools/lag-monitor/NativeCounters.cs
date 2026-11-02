using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using System.Threading;

namespace CoopLagMonitor {
    // Native PDH avoids WMI provider startup in every sample. No priorities, timer resolution,
    // GPU preferences, power settings or graphics settings are changed by this collector.
    public sealed class CounterReader : IDisposable {
        const uint DoubleFormat = 0x200 | 0x8000; // PDH_FMT_DOUBLE | PDH_FMT_NOCAP100
        const uint MoreData = 0x800007D2;
        IntPtr query;
        readonly Dictionary<string, IntPtr> handles = new Dictionary<string, IntPtr>();
        readonly List<string> initializationErrors = new List<string>();
        readonly int ownPid = Process.GetCurrentProcess().Id;
        readonly int cpuCount = Environment.ProcessorCount;
        Dictionary<string, int> previousProcessIds = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        readonly string bootUtc = DateTime.UtcNow.AddMilliseconds(-Environment.TickCount64).ToString("o");

        [StructLayout(LayoutKind.Explicit, Size = 16)]
        struct Value { [FieldOffset(0)] public uint status; [FieldOffset(8)] public double number; }
        [StructLayout(LayoutKind.Sequential)]
        struct Item { public IntPtr name; public Value value; }
        [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
        static extern uint PdhOpenQueryW(string source, UIntPtr user, out IntPtr query);
        [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
        static extern uint PdhAddEnglishCounterW(IntPtr query, string path, UIntPtr user, out IntPtr counter);
        [DllImport("pdh.dll")] static extern uint PdhCollectQueryData(IntPtr query);
        [DllImport("pdh.dll")] static extern uint PdhCloseQuery(IntPtr query);
        [DllImport("pdh.dll")] static extern uint PdhGetFormattedCounterValue(IntPtr counter, uint format,
            out uint type, out Value value);
        [DllImport("pdh.dll", CharSet = CharSet.Unicode)]
        static extern uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format,
            ref uint bytes, out uint count, IntPtr values);
        [StructLayout(LayoutKind.Sequential)]
        struct LastInput { public uint size; public uint tick; }
        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
        [DllImport("user32.dll", SetLastError = true)] static extern bool GetLastInputInfo(ref LastInput input);

        public CounterReader() {
            uint code = PdhOpenQueryW(null, UIntPtr.Zero, out query);
            if (code != 0) throw new InvalidOperationException("PdhOpenQuery: " + Hex(code));
            try {
                Add("cpuPct", @"\Processor(_Total)\% Processor Time");
                Add("privilegedPct", @"\Processor(_Total)\% Privileged Time");
                Add("dpcPct", @"\Processor(_Total)\% DPC Time");
                Add("interruptPct", @"\Processor(_Total)\% Interrupt Time");
                Add("availableMB", @"\Memory\Available MBytes");
                Add("pagesInPerSec", @"\Memory\Pages Input/sec");
                Add("pageReadsPerSec", @"\Memory\Page Reads/sec");
                Add("committedPct", @"\Memory\% Committed Bytes In Use");
                Add("processorQueue", @"\System\Processor Queue Length");
                Add("diskQueue", @"\PhysicalDisk(_Total)\Current Disk Queue Length");
                Add("diskReadMs", @"\PhysicalDisk(_Total)\Avg. Disk sec/Read");
                Add("diskWriteMs", @"\PhysicalDisk(_Total)\Avg. Disk sec/Write");
                Add("diskReadMBps", @"\PhysicalDisk(_Total)\Disk Read Bytes/sec");
                Add("diskWriteMBps", @"\PhysicalDisk(_Total)\Disk Write Bytes/sec");
                Add("cores", @"\Processor(*)\% Processor Time");
                Add("coreDpc", @"\Processor(*)\% DPC Time");
                Add("coreInterrupt", @"\Processor(*)\% Interrupt Time");
                Add("gpu", @"\GPU Engine(*)\Utilization Percentage");
                // The legacy Process counterset returns duplicate names (all java/pwsh instances),
                // which cannot be safely joined across counters. V2 includes PID in each name.
                Add("processId", @"\Process V2(*)\Process ID");
                Add("processCpu", @"\Process V2(*)\% Processor Time");
                Add("processWorking", @"\Process V2(*)\Working Set");
                Add("processPrivate", @"\Process V2(*)\Private Bytes");
                Add("processRead", @"\Process V2(*)\IO Read Bytes/sec");
                Add("processWrite", @"\Process V2(*)\IO Write Bytes/sec");
                code = PdhCollectQueryData(query); // Prime rate counters; caller waits before Sample.
                if (code != 0) throw new InvalidOperationException("PDH priming: " + Hex(code));
            } catch { Dispose(); throw; }
        }

        static string Hex(uint code) { return "0x" + code.ToString("X8"); }
        void Add(string key, string path) {
            IntPtr handle;
            uint code = PdhAddEnglishCounterW(query, path, UIntPtr.Zero, out handle);
            if (code == 0) handles.Add(key, handle);
            else initializationErrors.Add(key + ": add " + Hex(code));
        }

        double? Scalar(string key, List<string> errors) {
            IntPtr handle;
            if (!handles.TryGetValue(key, out handle)) return null;
            uint type; Value value;
            uint code = PdhGetFormattedCounterValue(handle, DoubleFormat, out type, out value);
            if (code != 0 || value.status > 1 || !Finite(value.number)) {
                errors.Add(key + ": read " + Hex(code) + ", status " + Hex(value.status));
                return null;
            }
            return value.number;
        }

        Dictionary<string, double> Array(string key, List<string> errors, bool transient) {
            var values = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
            IntPtr handle;
            if (!handles.TryGetValue(key, out handle)) return null;
            for (int attempt = 0; attempt < 3; attempt++) {
                uint bytes = 0, count;
                uint code = PdhGetFormattedCounterArrayW(handle, DoubleFormat, ref bytes, out count, IntPtr.Zero);
                if (code == 0 && count == 0) return values;
                if (code != MoreData || bytes == 0 || bytes > 32 * 1024 * 1024) {
                    errors.Add(key + ": size " + Hex(code)); return null;
                }
                IntPtr buffer = Marshal.AllocHGlobal((int)bytes);
                try {
                    code = PdhGetFormattedCounterArrayW(handle, DoubleFormat, ref bytes, out count, buffer);
                    if (code == MoreData) continue;
                    if (code != 0) { errors.Add(key + ": array " + Hex(code)); return null; }
                    int stride = Marshal.SizeOf(typeof(Item));
                    if ((long)count * stride > bytes) throw new InvalidOperationException("Invalid PDH array size");
                    int invalid = 0;
                    for (int index = 0; index < count; index++) {
                        Item item = Marshal.PtrToStructure<Item>(IntPtr.Add(buffer, index * stride));
                        if (item.value.status > 1 || !Finite(item.value.number)) { invalid++; continue; }
                        string name = Marshal.PtrToStringUni(item.name);
                        if (name != null) {
                            if (values.ContainsKey(name)) {
                                errors.Add(key + ": duplicate instance name " + name);
                                return null;
                            }
                            values.Add(name, item.value.number);
                        }
                    }
                    // Newly created/exited process and GPU instances can lack a valid rate interval.
                    if (invalid > 0 && !transient) errors.Add(key + ": " + invalid + " invalid rows");
                    return values;
                } finally { Marshal.FreeHGlobal(buffer); }
            }
            errors.Add(key + ": instances changed during all three reads"); return null;
        }

        static bool Finite(double value) { return !double.IsNaN(value) && !double.IsInfinity(value); }
        static double? Lookup(Dictionary<string, double> values, string key, double divisor = 1) {
            double value; return values != null && values.TryGetValue(key, out value) ? value / divisor : (double?)null;
        }
        static double? CoreMax(Dictionary<string, double> values) {
            if (values == null) return null;
            var cores = values.Where(pair => pair.Key != "_Total").Select(pair => pair.Value).ToArray();
            return cores.Length == 0 ? (double?)null : cores.Max();
        }

        public Snapshot Sample() {
            var result = new Snapshot();
            uint foregroundPid;
            GetWindowThreadProcessId(GetForegroundWindow(), out foregroundPid);
            result.foregroundPid = (int)foregroundPid;
            var input = new LastInput { size = (uint)Marshal.SizeOf(typeof(LastInput)) };
            if (GetLastInputInfo(ref input)) {
                // LASTINPUTINFO uses the 32-bit tick clock, including its normal wraparound.
                result.lastInputAgeMs = unchecked((uint)Environment.TickCount - input.tick);
            } else result.notices.Add("GetLastInputInfo unavailable: " + Marshal.GetLastWin32Error());
            result.errors.AddRange(initializationErrors);
            uint code = PdhCollectQueryData(query);
            if (code != 0) throw new InvalidOperationException("PdhCollectQueryData: " + Hex(code));
            foreach (string key in new [] { "cpuPct", "privilegedPct", "dpcPct", "interruptPct", "availableMB",
                    "pagesInPerSec", "pageReadsPerSec", "committedPct", "processorQueue", "diskQueue",
                    "diskReadMs", "diskWriteMs", "diskReadMBps", "diskWriteMBps" }) {
                double? value = Scalar(key, result.errors);
                if (key == "diskReadMs" || key == "diskWriteMs") value *= 1000;
                if (key == "diskReadMBps" || key == "diskWriteMBps") value /= 1048576;
                result.metrics[key] = value;
            }
            result.metrics["cpuMaxCorePct"] = CoreMax(Array("cores", result.errors, false));
            result.metrics["dpcMaxCorePct"] = CoreMax(Array("coreDpc", result.errors, false));
            result.metrics["interruptMaxCorePct"] = CoreMax(Array("coreInterrupt", result.errors, false));
            var gpu = Array("gpu", result.errors, true);
            result.gpuStatus = gpu == null ? "unavailable" : "ok";
            if (gpu != null) {
                var active = gpu.Where(pair => pair.Value >= 0.01).OrderByDescending(pair => pair.Value).ToArray();
                result.gpuTruncated = active.Length > 80;
                foreach (var pair in active.Take(80)) {
                    Match match = Regex.Match(pair.Key, @"^pid_(\d+)_");
                    int pid;
                    if (match.Success && int.TryParse(match.Groups[1].Value, out pid))
                        result.gpu.Add(new GpuRow { pid = pid, engine = pair.Key, pct = pair.Value });
                    else result.errors.Add("Unrecognized GPU instance: " + pair.Key);
                }
            }
            var ids = Array("processId", result.errors, true);
            var cpus = Array("processCpu", result.errors, true);
            var working = Array("processWorking", result.errors, true);
            var privateBytes = Array("processPrivate", result.errors, true);
            var read = Array("processRead", result.errors, true);
            var write = Array("processWrite", result.errors, true);
            if (ids == null) { previousProcessIds.Clear(); return result; }
            var candidates = new List<ProcessRow>();
            var currentProcessIds = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            foreach (var pair in ids) {
                if (pair.Value <= 0 || pair.Value > int.MaxValue || pair.Key == "_Total") continue;
                int oldPid;
                int pid = (int)pair.Value;
                bool sameIntervalOwner = previousProcessIds.TryGetValue(pair.Key, out oldPid) && oldPid == pid;
                currentProcessIds[pair.Key] = pid;
                candidates.Add(new ProcessRow {
                    pid = pid, name = pair.Key,
                    cpuPct = sameIntervalOwner ? Lookup(cpus, pair.Key, cpuCount) : null,
                    workingSetMB = Lookup(working, pair.Key, 1048576),
                    privateMB = Lookup(privateBytes, pair.Key, 1048576),
                    readMBps = sameIntervalOwner ? Lookup(read, pair.Key, 1048576) : null,
                    writeMBps = sameIntervalOwner ? Lookup(write, pair.Key, 1048576) : null
                });
            }
            previousProcessIds = currentProcessIds;
            var selected = new HashSet<int>(candidates.OrderByDescending(p => p.cpuPct ?? -1).Take(20).Select(p => p.pid));
            selected.UnionWith(candidates.OrderByDescending(p => (p.readMBps ?? 0) + (p.writeMBps ?? 0)).Take(10).Select(p => p.pid));
            selected.UnionWith(candidates.OrderByDescending(p => p.workingSetMB ?? -1).Take(5).Select(p => p.pid));
            foreach (var row in candidates) {
                string executableName = Regex.Replace(row.name, @"[_:# ]\(?\d+\)?$", "");
                if (row.pid == ownPid || Regex.IsMatch(executableName,
                        @"^(javaw?|coopguest-java|starsector|dwm|codex|chatgpt|msedgewebview2|system|pwsh|powershell|node|bun)(#\d+)?$",
                        RegexOptions.IgnoreCase)) selected.Add(row.pid);
            }
            foreach (var row in candidates.Where(p => selected.Contains(p.pid)).OrderByDescending(p => p.cpuPct ?? -1)) {
                try {
                    using (Process process = Process.GetProcessById(row.pid)) {
                        row.name = process.ProcessName;
                        row.startedUtc = process.StartTime.ToUniversalTime().ToString("o");
                    }
                } catch (Exception error) when (error is ArgumentException || error is InvalidOperationException || error is System.ComponentModel.Win32Exception) {
                    if (row.pid == 4) row.startedUtc = bootUtc;
                    else result.notices.Add("Process identity unavailable for " + row.pid + ": " + error.GetType().Name);
                }
                result.processes.Add(row);
            }
            return result;
        }

        public void Dispose() {
            if (query != IntPtr.Zero) { PdhCloseQuery(query); query = IntPtr.Zero; }
        }
    }

    public sealed class Snapshot {
        public int foregroundPid { get; set; }
        public double? lastInputAgeMs { get; set; }
        public Dictionary<string, double?> metrics { get; } = new Dictionary<string, double?>();
        public List<GpuRow> gpu { get; } = new List<GpuRow>();
        public string gpuStatus { get; set; }
        public bool gpuTruncated { get; set; }
        public List<ProcessRow> processes { get; } = new List<ProcessRow>();
        public List<string> errors { get; } = new List<string>();
        public List<string> notices { get; } = new List<string>();
    }
    public sealed class GpuRow {
        public int pid { get; set; }
        public string engine { get; set; }
        public double pct { get; set; }
    }
    public sealed class ProcessRow {
        public int pid { get; set; }
        public string name { get; set; }
        public string startedUtc { get; set; }
        public double? cpuPct { get; set; }
        public double? workingSetMB { get; set; }
        public double? privateMB { get; set; }
        public double? readMBps { get; set; }
        public double? writeMBps { get; set; }
    }

    public sealed class Heartbeat : IDisposable {
        readonly Thread thread;
        readonly object gate = new object();
        readonly List<double> delays = new List<double>();
        readonly int period;
        volatile bool stopped;
        public Heartbeat(int periodMs) {
            period = periodMs;
            thread = new Thread(Run) { IsBackground = true, Name = "CoopLagHeartbeat" };
            thread.Start();
        }
        void Run() {
            var clock = Stopwatch.StartNew();
            double due = clock.Elapsed.TotalMilliseconds + period;
            while (!stopped) {
                double remaining = due - clock.Elapsed.TotalMilliseconds;
                if (remaining > 0) Thread.Sleep((int)Math.Ceiling(remaining));
                double now = clock.Elapsed.TotalMilliseconds;
                lock (gate) { if (delays.Count < 10000) delays.Add(Math.Max(0, now - due)); }
                // Do not spin to catch up after a stall or alter the global timer resolution.
                due = now + period;
            }
        }
        public Dictionary<string, object> Drain() {
            double[] values;
            lock (gate) { values = delays.ToArray(); delays.Clear(); }
            System.Array.Sort(values);
            return new Dictionary<string, object> {
                { "periodMs", period }, { "count", values.Length },
                { "maxDelayMs", values.Length == 0 ? null : (object)values[values.Length - 1] },
                { "p95DelayMs", values.Length == 0 ? null : (object)values[(int)Math.Ceiling(values.Length * .95) - 1] }
            };
        }
        public void Dispose() { stopped = true; thread.Join(2000); }
    }
}
