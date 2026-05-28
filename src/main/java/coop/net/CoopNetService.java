package coop.net;

import coop.util.CoopLog;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class CoopNetService {
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final Queue<CoopMessages.Message> inbound = new ConcurrentLinkedQueue<>();
    private final Queue<CoopMessages.Message> outbound = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Channel> activeChannel = new AtomicReference<>();
    private final AtomicLong nextSeq = new AtomicLong();
    private final Object lifecycleLock = new Object();

    private volatile CoopConnectionRole role = CoopConnectionRole.NONE;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventLoopGroup clientGroup;
    private Channel serverChannel;

    public void startHost(int port) {
        synchronized (lifecycleLock) {
            shutdown();
            role = CoopConnectionRole.HOST;
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup(1);

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new CoopChannelInitializer());

            ChannelFuture bind = bootstrap.bind(port).syncUninterruptibly();
            serverChannel = bind.channel();
            CoopLog.info(CoopNetService.class, "Coop TCP host listening on port " + port);
        }
    }

    public void connect(String host, int port) {
        synchronized (lifecycleLock) {
            shutdown();
            role = CoopConnectionRole.GUEST;
            clientGroup = new NioEventLoopGroup(1);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(clientGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new CoopChannelInitializer());

            bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    CoopLog.info(CoopNetService.class, "Coop TCP guest connected to " + host + ":" + port);
                } else {
                    CoopLog.warn(CoopNetService.class, "Coop TCP guest failed to connect to " + host + ":" + port,
                            future.cause());
                }
            });
        }
    }

    public CoopConnectionRole role() {
        return role;
    }

    public boolean isConnected() {
        Channel channel = activeChannel.get();
        return channel != null && channel.isActive();
    }

    public long nextSeq() {
        return nextSeq.incrementAndGet();
    }

    public void send(CoopMessages.Message message) {
        outbound.add(message);
    }

    public void flushOutbound() {
        Channel channel = activeChannel.get();
        if (channel == null || !channel.isActive()) {
            return;
        }

        CoopMessages.Message message;
        while ((message = outbound.poll()) != null) {
            channel.writeAndFlush(CoopMessages.encode(message) + "\n");
        }
    }

    public CoopMessages.Message pollInbound() {
        return inbound.poll();
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            closeChannel(serverChannel);
            serverChannel = null;
            closeChannel(activeChannel.getAndSet(null));
            shutdownGroup(bossGroup);
            shutdownGroup(workerGroup);
            shutdownGroup(clientGroup);
            bossGroup = null;
            workerGroup = null;
            clientGroup = null;
            role = CoopConnectionRole.NONE;
        }
    }

    private void closeChannel(Channel channel) {
        if (channel != null) {
            channel.close();
        }
    }

    private void shutdownGroup(EventLoopGroup group) {
        if (group != null) {
            group.shutdownGracefully();
        }
    }

    private final class CoopChannelInitializer extends ChannelInitializer<SocketChannel> {
        @Override
        protected void initChannel(SocketChannel channel) {
            channel.pipeline().addLast(new LineBasedFrameDecoder(MAX_FRAME_BYTES));
            channel.pipeline().addLast(new StringDecoder(StandardCharsets.UTF_8));
            channel.pipeline().addLast(new StringEncoder(StandardCharsets.UTF_8));
            channel.pipeline().addLast(new CoopChannelHandler());
        }
    }

    private final class CoopChannelHandler extends SimpleChannelInboundHandler<String> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            Channel newChannel = ctx.channel();
            if (!activeChannel.compareAndSet(null, newChannel)) {
                CoopLog.warn(CoopNetService.class, "Coop TCP rejecting extra connection");
                newChannel.close();
                return;
            }
            CoopLog.info(CoopNetService.class, "Coop TCP channel active as " + role);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            activeChannel.compareAndSet(ctx.channel(), null);
            CoopLog.info(CoopNetService.class, "Coop TCP channel inactive as " + role);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String frame) {
            try {
                inbound.add(CoopMessages.decode(frame.trim()));
            } catch (RuntimeException ex) {
                CoopLog.warn(CoopNetService.class, "Coop TCP received invalid frame", ex);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            CoopLog.warn(CoopNetService.class, "Coop TCP channel exception", cause);
            ctx.close();
        }
    }
}
