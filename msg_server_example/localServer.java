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

public class localServer {
    public static final int YourHost = 7002;

    public static void main(String[] args) {
        localServer.load();
    }

    public static void load() {
        try {
            boolean isDBok = PostgresUtils.createTables();
            if (!isDBok) {
                System.exit(-1);
            }

            String users_db_status = PostgresUtils.insert_csv_users_table_if_it_is_empty();
            if (users_db_status.equals("TABLE_IS_NOT_EMPTY")) {
                System.out.println("В таблице есть данные");
            } else if (users_db_status.equals("ERROR_FORMAT")) {
                System.out.println("Неверный формат записи в CSV таблице");
                return;
            } else if (users_db_status.equals("ERROR")) {
                return;
            } else if (users_db_status.equals("YES")) {
                System.out.println("Данные при старте успешно вставлены!");
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
         
        System.out.println("Local Messenger Server Starting on port - " + YourHost);
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
                }).bind(YourHost).sync().channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
