package coop.launcher;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The launcher-to-launcher reachability check: the host holds a listener on its co-op port while its
 * launcher window is open, and the guest connects to it before either game starts.
 *
 * <h2>Why the listening side speaks first</h2>
 *
 * <p>The guest may end up talking to the host's <em>game</em> instead of the host's launcher - the
 * host pressed Launch already, or never opened the launcher at all. The game's accept path counts
 * failed password proofs per source address and puts a repeat offender into a doubling cooldown
 * ({@code CoopNetService.noteFailedProof}, red-team A3), and a probe must never be mistaken for one.
 *
 * <p>Verified against this build: {@code noteFailedProof} is reached from exactly two places, both in
 * {@code CoopNetPump} - the {@code LOBBY_HELLO} gate and the {@code SESSION_RESUME_REQUEST} gate -
 * and both need a parsed coop message carrying a wrong proof. A TCP connection that is opened and
 * closed without sending a byte never produces one, so it cannot contribute to the proof cooldown. It
 * does count as one of the five connection attempts per window in
 * {@code CoopNetService.noteConnectionAttemptLocked}, which is harmless for a single probe and is the
 * reason the prober connects once rather than retrying.
 *
 * <p>So the protocol is: on accept the host writes {@code COOP-LAUNCHER <version>\n} immediately. The
 * guest waits up to two seconds for that banner and <b>sends nothing until it arrives</b>. No banner
 * means "not the launcher", and the guest closes without a single byte on the wire.
 */
public final class CoopLauncherProbe {

    /** First token of the line the listening launcher writes on accept. */
    public static final String BANNER_PREFIX = "COOP-LAUNCHER ";
    /** What the prober sends once the banner has arrived. */
    public static final String PROBE_PREFIX = "PROBE ";
    /** What the listener echoes back. */
    public static final String PROBE_OK_PREFIX = "PROBE-OK ";

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private static final int BANNER_TIMEOUT_MILLIS = 2000;
    private static final int REPLY_TIMEOUT_MILLIS = 2000;
    private static final int UDP_TIMEOUT_MILLIS = 2000;
    private static final int LISTENER_IDLE_TIMEOUT_MILLIS = 5000;
    private static final int MAX_DATAGRAM_BYTES = 512;
    /**
     * Longest request line the listener will read (net-fix-3). The only line it ever expects is
     * {@code PROBE <32 hex chars>}; {@code readLine()} on the other hand buffers until a newline
     * arrives, so a client that opened the socket and sent megabytes without one grew the launcher's
     * heap for as long as it kept going.
     */
    static final int MAX_REQUEST_BYTES = 512;
    /**
     * Wall-clock ceiling on one served connection, measured from accept. {@code setSoTimeout} only
     * bounds <em>idle</em> time, so a client that sent one byte every four seconds held the serial
     * accept loop - and therefore the whole reachability check - open indefinitely.
     */
    static final long CONNECTION_DEADLINE_MILLIS = 5000L;

    private static final SecureRandom NONCES = new SecureRandom();

    private CoopLauncherProbe() {
    }

    /**
     * What the guest found.
     *
     * @param tcpReachable     the TCP connection was established
     * @param launcherAnswered the banner arrived, so the other end is a co-op launcher
     * @param launcherVersion  the version off the banner, {@code ""} when there was none
     * @param udpEchoed        a UDP datagram made the round trip
     * @param rttMillis        TCP probe round trip, {@code -1} when it was never measured
     * @param message          one sentence for the status pane
     */
    public record Result(boolean tcpReachable,
                         boolean launcherAnswered,
                         String launcherVersion,
                         boolean udpEchoed,
                         long rttMillis,
                         String message) {
        public Result {
            launcherVersion = launcherVersion == null ? "" : launcherVersion;
            message = message == null ? "" : message;
        }
    }

    /** A fresh probe nonce: hex, no spaces, so it survives a line-oriented protocol. */
    static String newNonce() {
        return Long.toHexString(NONCES.nextLong() >>> 1);
    }

    /**
     * The host side. Holds the co-op port so the guest can test it before either game starts; both
     * sockets are released by {@link #close()}, which the launcher calls before it starts the game so
     * the game can bind the same port.
     */
    public static final class HostListener implements Closeable {

        private final ServerSocket tcp;
        private final DatagramSocket udp;
        private final Thread tcpThread;
        private final Thread udpThread;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /**
         * The probe currently being served, if any. Closing the {@link ServerSocket} does not touch
         * a socket {@code accept()} already handed out, so without this a caller of {@link #close()}
         * waits out the join while the reader sits in its five-second timeout - and every caller is
         * on the event dispatch thread.
         */
        private volatile Socket accepted;

        private HostListener(ServerSocket tcp, DatagramSocket udp, String modVersion) {
            this.tcp = tcp;
            this.udp = udp;
            this.tcpThread = new Thread(() -> runTcp(modVersion), "coop-launcher-listen-tcp");
            this.udpThread = new Thread(this::runUdp, "coop-launcher-listen-udp");
            tcpThread.setDaemon(true);
            udpThread.setDaemon(true);
        }

        /**
         * Binds TCP and UDP on {@code port} and starts serving.
         *
         * @param port       the co-op port; {@code 0} picks an ephemeral one and binds UDP to match
         * @param modVersion goes out in the banner so the guest can see a version mismatch early
         * @throws IOException when either socket cannot bind, which is what "the game is already
         *         running" looks like
         */
        public static HostListener open(int port, String modVersion) throws IOException {
            return open(port, modVersion, null);
        }

        /** Test seam: bind to one address rather than the wildcard. */
        static HostListener open(int port, String modVersion, InetAddress bindAddress)
                throws IOException {
            Objects.requireNonNull(modVersion, "modVersion");
            IOException last = null;
            // With an explicit port there is exactly one attempt; with 0 the TCP bind picks a number
            // and UDP has to take the same one, which occasionally loses a race with another process.
            int attempts = port == 0 ? 10 : 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                ServerSocket tcp = new ServerSocket();
                try {
                    // SO_REUSEADDR is deliberately left off. On Windows it does not mean "rebind
                    // after TIME_WAIT" as it does on Unix - it lets a second socket bind a port
                    // another process already holds. That would turn the one thing this bind is for,
                    // finding out that the game is already running on this port, into a silent
                    // success and a listener nobody reaches.
                    tcp.bind(new InetSocketAddress(bindAddress, port), 8);
                    DatagramSocket udp = new DatagramSocket(null);
                    try {
                        udp.bind(new InetSocketAddress(bindAddress, tcp.getLocalPort()));
                    } catch (IOException ex) {
                        closeQuietly(udp);
                        throw ex;
                    }
                    HostListener listener = new HostListener(tcp, udp, modVersion);
                    listener.tcpThread.start();
                    listener.udpThread.start();
                    return listener;
                } catch (IOException ex) {
                    last = ex;
                    closeQuietly(tcp);
                }
            }
            throw last == null ? new IOException("could not bind port " + port) : last;
        }

        /** The port actually bound. */
        public int port() {
            return tcp.getLocalPort();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(tcp);
            closeQuietly(udp);
            Socket serving = accepted;
            if (serving != null) {
                closeQuietly(serving);
            }
            join(tcpThread);
            join(udpThread);
        }

        private void runTcp(String modVersion) {
            while (!closed.get()) {
                Socket socket;
                try {
                    socket = tcp.accept();
                } catch (IOException ex) {
                    return;
                }
                try (Socket client = socket) {
                    accepted = client;
                    long deadline = System.currentTimeMillis() + CONNECTION_DEADLINE_MILLIS;
                    client.setSoTimeout(LISTENER_IDLE_TIMEOUT_MILLIS);
                    OutputStream out = client.getOutputStream();
                    out.write((BANNER_PREFIX + modVersion + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    String line = readBoundedLine(client, deadline);
                    if (line != null && line.startsWith(PROBE_PREFIX)) {
                        String nonce = line.substring(PROBE_PREFIX.length()).trim();
                        out.write((PROBE_OK_PREFIX + nonce + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (IOException ex) {
                    // A probe that hangs up mid-exchange is not an error worth surfacing; the guest
                    // is the side that reports on this conversation.
                } finally {
                    accepted = null;
                }
            }
        }

        /**
         * One request line, capped at {@link #MAX_REQUEST_BYTES} and at {@code deadline}. Returns
         * null when the client hung up, overran the cap, or ran out of wall clock - all three are
         * "this is not a probe", and the caller's answer to that is to close the socket.
         */
        private String readBoundedLine(Socket client, long deadline) throws IOException {
            java.io.InputStream in = client.getInputStream();
            StringBuilder line = new StringBuilder(MAX_REQUEST_BYTES);
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return null;
                }
                // Re-armed every byte so the idle timeout can never outlast the absolute deadline.
                client.setSoTimeout((int) Math.min(LISTENER_IDLE_TIMEOUT_MILLIS, remaining));
                int value;
                try {
                    value = in.read();
                } catch (SocketTimeoutException ex) {
                    return null;
                }
                if (value < 0) {
                    return line.length() == 0 ? null : line.toString();
                }
                if (value == '\n') {
                    return line.toString();
                }
                if (value == '\r') {
                    continue;
                }
                if (line.length() >= MAX_REQUEST_BYTES) {
                    return null;
                }
                line.append((char) value);
            }
        }

        private void runUdp() {
            byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
            while (!closed.get()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udp.receive(packet);
                } catch (IOException ex) {
                    return;
                }
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                        StandardCharsets.UTF_8).trim();
                if (!text.startsWith(PROBE_PREFIX)) {
                    continue;
                }
                String reply = PROBE_OK_PREFIX + text.substring(PROBE_PREFIX.length()).trim();
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                try {
                    udp.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                } catch (IOException ex) {
                    // Same reasoning as the TCP side.
                }
            }
        }

        private static void join(Thread thread) {
            try {
                thread.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The guest side. One connection, one datagram, no retries. */
    public static final class GuestProber {

        private GuestProber() {
        }

        /** Runs the whole check. Never throws: every outcome is a {@link Result}. */
        public static Result probe(String host, int port) {
            String cleanHost = host == null ? "" : host.trim();
            if (cleanHost.isEmpty()) {
                return new Result(false, false, "", false, -1,
                        "Type the host's address first.");
            }
            if (port < 1 || port > 65535) {
                return new Result(false, false, "", false, -1,
                        "The port has to be between 1 and 65535.");
            }
            if (cleanHost.startsWith("[") && cleanHost.endsWith("]")) {
                cleanHost = cleanHost.substring(1, cleanHost.length() - 1);
            }

            InetAddress address;
            try {
                address = InetAddress.getByName(cleanHost);
            } catch (UnknownHostException ex) {
                return new Result(false, false, "", false, -1,
                        "The address \"" + cleanHost + "\" could not be looked up. Check the spelling.");
            }

            String banner = null;
            boolean tcpUp = false;
            long rtt = -1;
            String tcpMessage;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS);
                tcpUp = true;
                socket.setSoTimeout(BANNER_TIMEOUT_MILLIS);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException ex) {
                    line = null;
                }
                if (line == null || !line.startsWith(BANNER_PREFIX)) {
                    // Nothing was sent on this connection. See the class comment: sending here is
                    // what could look like a password guess to a running game.
                    return new Result(true, false, "", false, -1,
                            "Something is listening on " + cleanHost + ":" + port + " but it is not the"
                                    + " co-op launcher. If the host's game is already running, skip the"
                                    + " test and press Launch.");
                }
                banner = line.substring(BANNER_PREFIX.length()).trim();
                String nonce = newNonce();
                long start = System.nanoTime();
                OutputStream out = socket.getOutputStream();
                out.write((PROBE_PREFIX + nonce + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.setSoTimeout(REPLY_TIMEOUT_MILLIS);
                String reply;
                try {
                    reply = reader.readLine();
                } catch (SocketTimeoutException ex) {
                    reply = null;
                }
                if (reply == null || !reply.trim().equals(PROBE_OK_PREFIX + nonce)) {
                    return new Result(true, true, banner, false, -1,
                            "The host's launcher answered but its reply did not match the probe."
                                    + " Both sides need the same mod build.");
                }
                rtt = Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
                tcpMessage = "TCP reached " + cleanHost + ":" + port + ", host launcher version "
                        + banner + ", round trip " + rtt + " ms.";
            } catch (SocketTimeoutException ex) {
                return new Result(false, false, "", false, -1,
                        "No answer from " + cleanHost + ":" + port + " within 3 seconds. The port is"
                                + " probably not forwarded, or a firewall is dropping it.");
            } catch (IOException ex) {
                if (tcpUp) {
                    return new Result(true, banner != null, banner == null ? "" : banner, false, -1,
                            "The connection to " + cleanHost + ":" + port + " dropped mid-check ("
                                    + describe(ex) + ").");
                }
                return new Result(false, false, "", false, -1,
                        "Could not connect to " + cleanHost + ":" + port + " (" + describe(ex)
                                + "). Check the address, the port and the host's firewall.");
            }

            boolean udpEchoed = probeUdp(address, port);
            String udpMessage = udpEchoed
                    ? " UDP echoed."
                    : " UDP did not come back; the session would fall back to TCP, which works but"
                            + " costs latency.";
            return new Result(true, true, banner, udpEchoed, rtt, tcpMessage + udpMessage);
        }

        private static boolean probeUdp(InetAddress address, int port) {
            String nonce = newNonce();
            byte[] payload = (PROBE_PREFIX + nonce).getBytes(StandardCharsets.UTF_8);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(UDP_TIMEOUT_MILLIS);
                socket.send(new DatagramPacket(payload, payload.length, address, port));
                byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                socket.receive(reply);
                String text = new String(reply.getData(), reply.getOffset(), reply.getLength(),
                        StandardCharsets.UTF_8).trim();
                return text.equals(PROBE_OK_PREFIX + nonce);
            } catch (IOException ex) {
                return false;
            }
        }
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing is best effort; there is nothing left to do about a failure here.
        }
    }

    private static void closeQuietly(DatagramSocket socket) {
        socket.close();
    }
}
