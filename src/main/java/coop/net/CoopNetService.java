package coop.net;

import coop.util.CoopLog;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public class CoopNetService {
    private static final int MAX_FRAME_BYTES = 64 * 1024;
    private static final int READ_BUFFER_BYTES = 8 * 1024;
    private static final long CONNECT_RETRY_DELAY_MILLIS = 500L;
    private static final String EXTRA_CONNECTION_REJECT_REASON = "Host already has an active connection";

    private final Queue<CoopMessages.Message> inbound = new ConcurrentLinkedQueue<>();
    private final Queue<CoopMessages.Message> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicLong nextSeq = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_BYTES);
    private final byte[] inboundFrame = new byte[MAX_FRAME_BYTES];

    private CoopConnectionRole role = CoopConnectionRole.NONE;
    private ServerSocketChannel serverChannel;
    private SocketChannel activeChannel;
    private SocketChannel pendingConnectChannel;
    private ByteBuffer pendingWrite;
    private int inboundFrameLength;
    private String connectHost;
    private int connectPort;
    private long nextConnectAttemptAtMillis;
    private boolean connectFailureLogged;
    private boolean discardingOversizedFrame;

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
            pollNetworkLocked();
        }
    }

    public CoopConnectionRole role() {
        synchronized (lifecycleLock) {
            return role;
        }
    }

    public boolean isConnected() {
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            return activeChannel != null && activeChannel.isOpen() && activeChannel.isConnected();
        }
    }

    public long nextSeq() {
        return nextSeq.incrementAndGet();
    }

    public void send(CoopMessages.Message message) {
        outbound.add(message);
    }

    public void flushOutbound() {
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            flushOutboundLocked();
        }
    }

    public CoopMessages.Message pollInbound() {
        synchronized (lifecycleLock) {
            pollNetworkLocked();
            return inbound.poll();
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
        CoopLog.info(CoopNetService.class, "Coop TCP channel active as " + role);
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
                pendingWrite = ByteBuffer.wrap((CoopMessages.encode(message) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
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
        serverChannel = null;
        activeChannel = null;
        pendingConnectChannel = null;
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
