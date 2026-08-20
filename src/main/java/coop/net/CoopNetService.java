package coop.net;

import coop.util.CoopLog;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class CoopNetService {
    /**
     * TCP frame sanity cap. This is corruption protection, not a transport limit — TCP is a stream,
     * so any frame size survives the wire; a frame this large only ever means a corrupted length or a
     * runaway encoder. Raised from 64 KB on 2026-08-20: NPC_FLEET_SET with the Phase 16 per-member
     * hullmod fields crossed 64 KB in a busy sector, and every set rebroadcast was silently discarded
     * by the receiver — the guest's mirror population froze. Must match on both installs (handshake
     * enforces same build). {@link #WARN_FRAME_BYTES} gives the early warning before this cliff.
     */
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    /** Soft threshold: an outbound frame this big logs once per message type, so growth is loud. */
    private static final int WARN_FRAME_BYTES = 256 * 1024;
    /** UDP receive buffer — sized for the datagram path, not the TCP frame cap. */
    private static final int DATAGRAM_BUFFER_BYTES = 64 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;
    private static final int MAX_DATAGRAM_BYTES = 60 * 1024;
    private static final long CONNECT_RETRY_DELAY_MILLIS = 500L;
    private static final String EXTRA_CONNECTION_REJECT_REASON = "Host already has an active connection";

    private final Queue<CoopMessages.Message> inbound = new ConcurrentLinkedQueue<>();
    private final Queue<CoopMessages.Message> outbound = new ConcurrentLinkedQueue<>();
    // High-frequency state datagrams (UDP). Kept separate from the reliable TCP control queues.
    // Netty is deliberately avoided here: Starsector's script sandbox blocks Netty's reflection
    // (see CoopNetServiceSandboxCompatibilityTest), so coop networking uses java.nio throughout.
    private final Queue<String> inboundDatagrams = new ConcurrentLinkedQueue<>();
    private final Queue<String> outboundDatagrams = new ConcurrentLinkedQueue<>();
    // One warning per message type per service lifetime; guarded by lifecycleLock (flush path).
    private final java.util.Set<CoopMessages.Type> largeFrameWarned =
            java.util.EnumSet.noneOf(CoopMessages.Type.class);
    private final AtomicLong nextSeq = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final ByteBuffer datagramBuffer = ByteBuffer.allocate(DATAGRAM_BUFFER_BYTES);
    private final byte[] inboundFrame = new byte[MAX_FRAME_BYTES];

    private CoopConnectionRole role = CoopConnectionRole.NONE;
    private ServerSocketChannel serverChannel;
    private SocketChannel activeChannel;
    /**
     * Cached answer for {@link #isConnected()}. Derived from {@code activeChannel} by
     * {@link #refreshConnectedLocked()}, which every path that can change it calls; volatile so the
     * fast path can read it without taking {@code lifecycleLock}.
     */
    private volatile boolean connected;
    private SocketChannel pendingConnectChannel;
    private DatagramChannel udpChannel;
    private SocketAddress udpRemoteAddress;
    /**
     * Peer address pinned from the established TCP connection. UDP datagrams are only accepted from
     * this address, and (on the host) only such a datagram may teach us the UDP return address —
     * otherwise a stray LAN packet could blackhole the motion stream. The peer's UDP *port* is still
     * learned from the first valid datagram, since it legitimately differs from the TCP port.
     */
    private InetAddress pinnedPeerAddress;
    private boolean foreignDatagramWarned;
    private ByteBuffer pendingWrite;
    private int inboundFrameLength;
    private String connectHost;
    private int connectPort;
    private long nextConnectAttemptAtMillis;
    private boolean connectFailureLogged;
    private boolean discardingOversizedFrame;
    private boolean datagramSendFailureLogged;

    public void startHost(int port) {
        synchronized (lifecycleLock) {
            shutdownLocked();
            role = CoopConnectionRole.HOST;
            try {
                ServerSocketChannel channel = ServerSocketChannel.open();
                channel.configureBlocking(false);
                channel.socket().setReuseAddress(true);
                channel.bind(new InetSocketAddress(port));
                serverChannel = channel;
                openUdpLocked(new InetSocketAddress(port), null);
                CoopLog.info(CoopNetService.class, "Coop TCP host listening on port " + port);
            } catch (Exception ex) {
                shutdownLocked();
                throw new IllegalStateException("Unable to start coop TCP host on port " + port, ex);
            }
        }
    }

    public void connect(String host, int port) {
        synchronized (lifecycleLock) {
            shutdownLocked();
            role = CoopConnectionRole.GUEST;
            connectHost = host;
            connectPort = port;
            nextConnectAttemptAtMillis = 0L;
            connectFailureLogged = false;
            // Guest binds an ephemeral UDP port and sends to the host's known address; the host
            // learns the guest's UDP address from the first datagram it receives.
            openUdpLocked(new InetSocketAddress(0), new InetSocketAddress(host, port));
            pollNetworkLocked();
        }
    }

    public CoopConnectionRole role() {
        synchronized (lifecycleLock) {
            return role;
        }
    }

    /**
     * Whether the TCP channel is up. Reads a cached flag while connected — the pump and the battle
     * bridge ask this ~11 times a frame, and every one of those calls used to run a full
     * {@link #pollNetworkLocked()} (accept + read loop + datagram receive), which is where most of the
     * measured ~3000 socket syscalls/s came from (perf audit #10).
     *
     * <p>The flag is refreshed by {@link #refreshConnectedLocked()} from every path that can change the
     * answer: every poll, and every mutation of {@code activeChannel} (attach, close, shutdown). A peer
     * that vanishes is only ever discovered inside a poll (a {@code read} of -1), and the pump polls at
     * the head of its frame via {@link #flushOutbound()} — immediately before {@code detectPeerDisconnect}
     * reads this — so a transition is still observed on the frame it happens.
     *
     * <p>While the flag is false this still polls, and deliberately: a connection can only be
     * <em>established</em> inside a poll (host accept, guest connect-retry), and callers spinning on
     * this to wait for a peer — including a guest reconnecting mid-battle, when the campaign pump is
     * not running to poll for it — must keep driving that.
     */
    public boolean isConnected() {
        if (connected) {
            return true;
        }
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            return connected;
        }
    }

    public long nextSeq() {
        return nextSeq.incrementAndGet();
    }

    public void send(CoopMessages.Message message) {
        outbound.add(message);
    }

    /**
     * Polls the network, then writes everything queued. This is the frame's poll: the pump calls it at
     * the head and the tail of {@code advance()}, so everything that arrived before the frame started
     * is already queued by the time the drains below run.
     */
    public void flushOutbound() {
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            flushOutboundLocked();
            flushDatagramsLocked();
        }
    }

    /**
     * Next queued TCP message, or null. Drains the queue first and only polls when it runs dry, rather
     * than polling on every call (perf audit #10): the drain loop that empties a 40-message backlog
     * used to cost 40 accept+read+recvfrom passes.
     *
     * <p>The delivered sequence is unchanged. The frame's poll already happened in
     * {@link #flushOutbound()}, so everything that arrived before the frame started is in the queue;
     * the dry poll at the end of the drain is what still lets a message that landed <em>during</em> the
     * frame be processed by it, exactly as before.
     */
    public CoopMessages.Message pollInbound() {
        synchronized (lifecycleLock) {
            CoopMessages.Message queued = inbound.poll();
            if (queued != null) {
                return queued;
            }
            pollNetworkLocked();
            return inbound.poll();
        }
    }

    /** Queues a best-effort UDP datagram (high-frequency state). Dropped if no peer address known. */
    public void sendDatagram(String payload) {
        if (payload == null) {
            return;
        }
        outboundDatagrams.add(payload);
    }

    /** Returns the next received UDP datagram payload, or null. Drains first, polls dry — see
     * {@link #pollInbound()} for why that preserves what a frame ingests. */
    public String pollDatagram() {
        synchronized (lifecycleLock) {
            String queued = inboundDatagrams.poll();
            if (queued != null) {
                return queued;
            }
            pollNetworkLocked();
            return inboundDatagrams.poll();
        }
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            shutdownLocked();
        }
    }

    private void pollNetworkLocked() {
        try {
            acceptHostConnectionLocked();
            progressGuestConnectionLocked();
            readAvailableLocked();
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP polling failed", ex);
            closeActiveChannelLocked(activeChannel);
            closeChannel(pendingConnectChannel);
            pendingConnectChannel = null;
        }
        readDatagramsLocked();
        refreshConnectedLocked();
    }

    /** Recomputes the {@link #isConnected()} cache from the live channel. Cheap: two field reads. */
    private void refreshConnectedLocked() {
        SocketChannel channel = activeChannel;
        connected = channel != null && channel.isOpen() && channel.isConnected();
    }

    private void openUdpLocked(InetSocketAddress bindAddress, SocketAddress remoteAddress) {
        try {
            DatagramChannel channel = DatagramChannel.open();
            channel.configureBlocking(false);
            channel.socket().setReuseAddress(true);
            channel.bind(bindAddress);
            udpChannel = channel;
            udpRemoteAddress = remoteAddress;
            datagramSendFailureLogged = false;
            CoopLog.info(CoopNetService.class, "Coop UDP datagram channel bound to " + bindAddress);
        } catch (Exception ex) {
            udpChannel = null;
            udpRemoteAddress = null;
            CoopLog.warn(CoopNetService.class, "Coop UDP datagram channel unavailable; "
                    + "campaign state stream disabled (TCP control unaffected)", ex);
        }
    }

    private void readDatagramsLocked() {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            return;
        }
        try {
            datagramBuffer.clear();
            SocketAddress source = channel.receive(datagramBuffer);
            while (source != null) {
                boolean full = !datagramBuffer.hasRemaining();
                if (!isPinnedPeerLocked(source)) {
                    // Drop before it can teach us a return address. Warn once per session so a noisy
                    // LAN cannot flood the log.
                    if (!foreignDatagramWarned) {
                        foreignDatagramWarned = true;
                        CoopLog.warn(CoopNetService.class, "Coop UDP ignoring datagram from non-peer source "
                                + source + " (pinned peer "
                                + (pinnedPeerAddress == null ? "<none>" : pinnedPeerAddress.getHostAddress()) + ")");
                    }
                } else if (full) {
                    // Buffer filled to capacity: the datagram was at least as large as the buffer and
                    // may be truncated. Decoding it would yield a corrupt payload, so discard it.
                    CoopLog.warn(CoopNetService.class, "Coop UDP discarding truncated datagram from " + source
                            + " (filled the " + datagramBuffer.capacity() + "-byte buffer)");
                } else {
                    if (role == CoopConnectionRole.HOST) {
                        // Learn (or relearn, on guest reconnect) the guest's UDP return address.
                        udpRemoteAddress = source;
                    }
                    datagramBuffer.flip();
                    byte[] bytes = new byte[datagramBuffer.remaining()];
                    datagramBuffer.get(bytes);
                    inboundDatagrams.add(new String(bytes, StandardCharsets.UTF_8));
                }
                datagramBuffer.clear();
                source = channel.receive(datagramBuffer);
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop UDP receive failed", ex);
        }
    }

    /**
     * True when the datagram may be accepted from this source.
     *
     * <p>Two stages. The address is pinned when the TCP connection is established. The <em>port</em>
     * cannot be pinned then (the peer's UDP port legitimately differs from its TCP port), so it is
     * locked instead by the first datagram that passes the address check; from that point the full
     * address+port must match.
     *
     * <p>Address-only checking is not enough on its own: on loopback — and behind a shared NAT —
     * any other process has the same address, so a stray packet would still pass and, on the host,
     * re-teach the return address and blackhole the motion stream. Locking the port after the first
     * valid datagram closes that. A reconnect re-establishes TCP, which clears both the pin and the
     * learned address, so the peer is free to come back on a new port.
     */
    private boolean isPinnedPeerLocked(SocketAddress source) {
        if (pinnedPeerAddress == null) {
            return true;
        }
        if (!(source instanceof InetSocketAddress inet) || inet.getAddress() == null) {
            return false;
        }
        if (!pinnedPeerAddress.equals(inet.getAddress())) {
            return false;
        }
        // Address matches. If we already know the peer's UDP port for this connection, require it.
        return udpRemoteAddress == null || udpRemoteAddress.equals(source);
    }

    private static InetAddress peerAddressOf(SocketChannel channel) {
        try {
            SocketAddress remote = channel.getRemoteAddress();
            return remote instanceof InetSocketAddress inet ? inet.getAddress() : null;
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop could not read TCP peer address for UDP pinning", ex);
            return null;
        }
    }

    private void flushDatagramsLocked() {
        DatagramChannel channel = udpChannel;
        if (channel == null) {
            outboundDatagrams.clear();
            return;
        }
        SocketAddress remote = udpRemoteAddress;
        if (remote == null) {
            // No peer address known yet (host before first guest datagram). Drop; the next 10 Hz
            // snapshot supersedes anything queued, so there is no value in buffering stale state.
            outboundDatagrams.clear();
            return;
        }

        String payload;
        while ((payload = outboundDatagrams.poll()) != null) {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_DATAGRAM_BYTES) {
                CoopLog.warn(CoopNetService.class,
                        "Coop UDP dropping oversized datagram (" + bytes.length + " bytes)");
                continue;
            }
            try {
                channel.send(ByteBuffer.wrap(bytes), remote);
            } catch (Exception ex) {
                if (!datagramSendFailureLogged) {
                    CoopLog.warn(CoopNetService.class, "Coop UDP send failed; dropping datagram", ex);
                    datagramSendFailureLogged = true;
                }
            }
        }
    }

    private void acceptHostConnectionLocked() throws Exception {
        if (role != CoopConnectionRole.HOST || serverChannel == null) {
            return;
        }

        SocketChannel accepted = serverChannel.accept();
        if (accepted == null) {
            return;
        }

        if (activeChannel != null) {
            rejectExtraConnectionLocked(accepted);
            return;
        }

        if (!attachChannelLocked(accepted)) {
            CoopLog.warn(CoopNetService.class, "Coop TCP rejecting extra connection");
            rejectExtraConnectionLocked(accepted);
        }
    }

    private void rejectExtraConnectionLocked(SocketChannel channel) {
        try {
            channel.configureBlocking(true);
            channel.socket().setTcpNoDelay(true);
            CoopMessages.Message reject = CoopMessages.lobbyReject(
                    nextSeq(),
                    System.currentTimeMillis(),
                    EXTRA_CONNECTION_REJECT_REASON);
            ByteBuffer frame = ByteBuffer.wrap((CoopMessages.encode(reject) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            while (frame.hasRemaining()) {
                channel.write(frame);
            }
            CoopLog.warn(CoopNetService.class, "Coop TCP rejected extra connection with lobby reject");
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to reject extra connection cleanly", ex);
        } finally {
            closeChannel(channel);
        }
    }

    private void progressGuestConnectionLocked() throws Exception {
        if (role != CoopConnectionRole.GUEST || activeChannel != null) {
            return;
        }

        if (pendingConnectChannel != null) {
            try {
                if (pendingConnectChannel.finishConnect()) {
                    SocketChannel connected = pendingConnectChannel;
                    pendingConnectChannel = null;
                    attachChannelLocked(connected);
                    connectFailureLogged = false;
                    CoopLog.info(CoopNetService.class,
                            "Coop TCP guest connected to " + connectHost + ":" + connectPort);
                    return;
                }
            } catch (Exception ex) {
                closeChannel(pendingConnectChannel);
                pendingConnectChannel = null;
                scheduleConnectRetryLocked(ex);
            }
        }

        long now = System.currentTimeMillis();
        if (pendingConnectChannel != null || now < nextConnectAttemptAtMillis) {
            return;
        }

        beginConnectAttemptLocked(now);
    }

    private void beginConnectAttemptLocked(long now) {
        try {
            SocketChannel channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            if (channel.connect(new InetSocketAddress(connectHost, connectPort))) {
                attachChannelLocked(channel);
                connectFailureLogged = false;
                CoopLog.info(CoopNetService.class,
                        "Coop TCP guest connected to " + connectHost + ":" + connectPort);
            } else {
                pendingConnectChannel = channel;
            }
        } catch (Exception ex) {
            scheduleConnectRetryLocked(ex);
            nextConnectAttemptAtMillis = now + CONNECT_RETRY_DELAY_MILLIS;
        }
    }

    private void scheduleConnectRetryLocked(Exception ex) {
        nextConnectAttemptAtMillis = System.currentTimeMillis() + CONNECT_RETRY_DELAY_MILLIS;
        if (!connectFailureLogged) {
            CoopLog.warn(CoopNetService.class,
                    "Coop TCP guest failed to connect to " + connectHost + ":" + connectPort + "; will retry", ex);
            connectFailureLogged = true;
        }
    }

    private boolean attachChannelLocked(SocketChannel channel) throws Exception {
        if (role == CoopConnectionRole.NONE || activeChannel != null) {
            return false;
        }

        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        activeChannel = channel;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
        pinnedPeerAddress = peerAddressOf(channel);
        foreignDatagramWarned = false;
        if (role == CoopConnectionRole.HOST) {
            // Relearn the guest's UDP port for this connection. The host does not run shutdownLocked
            // when a guest merely reconnects, so without this the previous guest's port would stay
            // locked and the new guest's datagrams would be rejected for the rest of the session.
            udpRemoteAddress = null;
        }
        refreshConnectedLocked();
        CoopLog.info(CoopNetService.class, "Coop TCP channel active as " + role
                + (pinnedPeerAddress == null ? "" : " (UDP pinned to " + pinnedPeerAddress.getHostAddress() + ")"));
        return true;
    }

    private void readAvailableLocked() throws Exception {
        SocketChannel channel = activeChannel;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }

        readBuffer.clear();
        int read = channel.read(readBuffer);
        while (read > 0) {
            readBuffer.flip();
            while (readBuffer.hasRemaining()) {
                appendInboundByte(readBuffer.get());
            }
            readBuffer.clear();
            read = channel.read(readBuffer);
        }

        if (read < 0) {
            closeActiveChannelLocked(channel);
        }
    }

    private void appendInboundByte(byte value) {
        int unsigned = value & 0xff;
        if (unsigned == '\n') {
            if (discardingOversizedFrame) {
                inboundFrameLength = 0;
                discardingOversizedFrame = false;
                return;
            }
            String frame = new String(inboundFrame, 0, inboundFrameLength, StandardCharsets.UTF_8);
            inboundFrameLength = 0;
            handleFrame(frame.trim());
            return;
        }

        if (unsigned == '\r' || discardingOversizedFrame) {
            return;
        }

        if (inboundFrameLength >= MAX_FRAME_BYTES) {
            CoopLog.warn(CoopNetService.class, "Coop TCP received oversized frame");
            inboundFrameLength = 0;
            discardingOversizedFrame = true;
            return;
        }

        inboundFrame[inboundFrameLength] = (byte) unsigned;
        inboundFrameLength++;
    }

    private void handleFrame(String frame) {
        if (frame.isEmpty()) {
            return;
        }
        try {
            inbound.add(CoopMessages.decode(frame));
        } catch (RuntimeException ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP received invalid frame", ex);
        }
    }

    private void flushOutboundLocked() {
        SocketChannel channel = activeChannel;
        if (channel == null || !channel.isOpen() || !channel.isConnected()) {
            return;
        }

        try {
            if (pendingWrite != null && !writePendingLocked(channel)) {
                return;
            }
            pendingWrite = null;

            CoopMessages.Message message;
            while ((message = outbound.poll()) != null) {
                byte[] frame = (CoopMessages.encode(message) + "\n").getBytes(StandardCharsets.UTF_8);
                if (frame.length > MAX_FRAME_BYTES) {
                    // The receiver's inbound cap would discard it anyway; dropping here keeps the
                    // failure on the sender's log where the message type is still known.
                    CoopLog.warn(CoopNetService.class, "Coop TCP dropping oversized outbound "
                            + message.type() + " frame (" + frame.length + " bytes, cap "
                            + MAX_FRAME_BYTES + ")");
                    continue;
                }
                if (frame.length > WARN_FRAME_BYTES && largeFrameWarned.add(message.type())) {
                    CoopLog.warn(CoopNetService.class, "Coop TCP outbound " + message.type()
                            + " frame is " + frame.length + " bytes (soft threshold "
                            + WARN_FRAME_BYTES + ", hard cap " + MAX_FRAME_BYTES
                            + "); consider shrinking this message before it hits the cap");
                }
                pendingWrite = ByteBuffer.wrap(frame);
                if (!writePendingLocked(channel)) {
                    return;
                }
                pendingWrite = null;
            }
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to flush outbound messages", ex);
            closeActiveChannelLocked(channel);
        }
    }

    private boolean writePendingLocked(SocketChannel channel) throws Exception {
        while (pendingWrite.hasRemaining()) {
            if (channel.write(pendingWrite) == 0) {
                return false;
            }
        }
        return true;
    }

    private void closeActiveChannelLocked(SocketChannel channel) {
        if (channel == null || activeChannel != channel) {
            return;
        }

        activeChannel = null;
        connected = false;
        pendingWrite = null;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
        closeChannel(channel);
        CoopLog.info(CoopNetService.class, "Coop TCP channel inactive as " + role);
        if (role == CoopConnectionRole.GUEST) {
            nextConnectAttemptAtMillis = System.currentTimeMillis() + CONNECT_RETRY_DELAY_MILLIS;
        }
    }

    private void shutdownLocked() {
        closeChannel(serverChannel);
        closeChannel(activeChannel);
        closeChannel(pendingConnectChannel);
        closeChannel(udpChannel);
        serverChannel = null;
        activeChannel = null;
        connected = false;
        pendingConnectChannel = null;
        udpChannel = null;
        udpRemoteAddress = null;
        datagramSendFailureLogged = false;
        inboundDatagrams.clear();
        outboundDatagrams.clear();
        // TCP queues too: a session restarted inside the same game process would otherwise replay
        // leftovers (a stale HANDSHAKE_RESULT, say) into the fresh connection.
        inbound.clear();
        outbound.clear();
        pinnedPeerAddress = null;
        foreignDatagramWarned = false;
        pendingWrite = null;
        connectHost = null;
        connectPort = 0;
        nextConnectAttemptAtMillis = 0L;
        connectFailureLogged = false;
        inboundFrameLength = 0;
        discardingOversizedFrame = false;
        role = CoopConnectionRole.NONE;
    }

    private void closeChannel(java.nio.channels.Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (Exception ex) {
            CoopLog.warn(CoopNetService.class, "Coop TCP failed to close channel", ex);
        }
    }
}
