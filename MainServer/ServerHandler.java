import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@Sharable
public class ServerHandler extends ChannelInboundHandlerAdapter {
    @Override
    public void channelActive(ChannelHandlerContext ctx) {}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Теперь msg — это гарантированно одна чистая строка String благодаря LineBasedFrameDecoder
        String text = ((String) msg).trim(); 

        if (text.startsWith("GET_HOST")) {
            handleGetHost(ctx, text);
        } else {
            System.out.println("Неизвестная Команда!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("NO_UNKNOW_CMD" + "\n").getBytes()));
        }
    }

    public void handleGetHost(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|");
        if (parts.length != 2) {
            System.out.println("Неверный Формат Команды!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("INVALID_CMD_FORMAT" + "\n").getBytes()));
        }

        String key = parts[1];

        PostgresUtils.get_host(key)
            .thenAccept(rs -> {
                ctx.writeAndFlush(Unpooled.copiedBuffer((rs + "\n").getBytes()));
            })
            .exceptionally(ex -> {
                System.out.println("Ошибка SQL - " + ex.getMessage());
                return null;
            });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("Error - " + cause.getMessage());
        ctx.close();
    }
}