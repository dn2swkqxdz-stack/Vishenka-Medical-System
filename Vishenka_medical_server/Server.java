import java.nio.charset.StandardCharsets;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;

public class Server {
    public static final int YourHost = 6667;

    public static void main(String[] args) {
        Server node = new Server();
        node.load();
    }

    public static void load() {
        try {
            boolean isDBok = PostgresUtils.createTables();
            if (!isDBok) {
                System.exit(-1);
            }
            startNioServer();
        } catch (InterruptedException e) {
            System.out.println("Main Error - " + e.getMessage());
        }
    }

    public static void startNioServer() throws InterruptedException {
        int pCores = 8; 
        EventLoopGroup boss = new KQueueEventLoopGroup(1);
        EventLoopGroup worker = new KQueueEventLoopGroup(pCores); 
         
        System.out.println("Server Starting on port - " + YourHost);
        System.out.println(KQueue.isAvailable());
        System.out.println(KQueue.unavailabilityCause());

        try {
            new ServerBootstrap()
                .group(boss, worker)
                .channel(KQueueServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 65535)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new io.netty.handler.codec.LineBasedFrameDecoder(8192));
                        ch.pipeline().addLast(new io.netty.handler.codec.string.StringDecoder(StandardCharsets.UTF_8));
                        ch.pipeline().addLast(new ServerHandler()); 
                    }
                }).bind(6667).sync().channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}

