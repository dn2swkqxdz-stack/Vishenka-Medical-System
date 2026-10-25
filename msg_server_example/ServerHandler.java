import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

@Sharable
public class ServerHandler extends ChannelInboundHandlerAdapter {

    private static final Map<String, Channel> CTX_BY_EMAIL = new ConcurrentHashMap<>();
    private static final AttributeKey<String> EMAIL_BY_CTX = AttributeKey.valueOf("state");
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Теперь msg — это гарантированно одна чистая строка String благодаря LineBasedFrameDecoder
        String text = ((String) msg).trim(); 

        System.out.println("=== СЕРВЕР ПОЛУЧИЛ СТРОКУ: [" + text + "] ===");

        if (text.startsWith("LOGIN")) {
            handleLogin(ctx, text);
        } else if (text.startsWith("PUT_KEYS")) {
            handlePutKeys(ctx, text);
        } else if (text.startsWith("GET_BUNDLE")) {
            handleGetKeys(ctx, text);
        } else if (text.startsWith("PM")) {
            sendPrivateMsg(ctx, text);
        } else if (text.startsWith("OFFLINE_MSGS")) {
            getMessages(ctx, text);
        } else {
            System.out.println("Неизвестная Команда!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("NO_UNKNOW_CMD" + "\n").getBytes()));
        }
    }

    public void getMessages(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|");
        //PM_GET|||sender|||recipient

        if (parts.length != 3) {
            System.out.println("Неверный Формат!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("ERROR_FORMAT" + "\n").getBytes()));
            return;
        }

        // 1. Узнаем, кто НА САМОМ ДЕЛЕ сидит за этим сокетом
        String currentUserEmail = ctx.channel().attr(EMAIL_BY_CTX).get();
        if (currentUserEmail == null) {
            ctx.writeAndFlush(Unpooled.copiedBuffer("UNAUTHORIZED\n".getBytes()));
            return;
        }

        String sender = parts[1];
        String recipient = parts[2];

        // 2. Жесткая проверка: ты имеешь право забирать оффлайн-сообщения, 
        // ТОЛЬКО если ты являешься получателем (recipient) этого сообщения!
        if (!currentUserEmail.equals(recipient)) {
            ctx.writeAndFlush(Unpooled.copiedBuffer("ACCESS_DENIED\n".getBytes()));
            return;
        }

        PostgresUtils.get_messages(sender, recipient)
            .thenAccept(rs -> {
                ctx.writeAndFlush(Unpooled.copiedBuffer((rs + "\n").getBytes()));
                System.out.println(rs);
            })
            .exceptionally(ex -> {
                System.out.println("Ошибка SQL - " + ex.getMessage());
                return null;
            });
    }

    public void sendPrivateMsg(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|");
        //PM|||кому|||тип_сообщения|||зашифрованный_текст

        if (parts.length != 4) {
            System.out.println("Неверный Формат!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("ERROR_FORMAT" + "\n").getBytes()));
            return;
        }

        Channel to_ch = CTX_BY_EMAIL.get(parts[1]);

        byte[] msg_blob = java.util.Base64.getDecoder().decode(parts[3].trim());

        String sender = ctx.channel().attr(EMAIL_BY_CTX).get();
        String recipient = parts[1];

        // ИСПРАВЛЕНО: Защита от падения, если адресат почивать изволит (оффлайн)
        if (to_ch != null && to_ch.isActive()) {
            to_ch.writeAndFlush(Unpooled.copiedBuffer(("INCOMING_PM|||" + sender + "|||" + parts[2] + "|||" + parts[3]  + "|||" + Instant.now().toString() + "\n").getBytes()));
            System.out.println("Депеша успешно переслана к " + parts[1]);
        } else {
            PostgresUtils.put_offline_msg(sender, recipient, msg_blob, UUID.randomUUID().toString(), Instant.now().toString(), Integer.parseInt(parts[2]))
                .thenAccept(rs -> { 
                    System.out.println("Сообщение успешно положено в БД и ждет пока его заберут");
                })
                .exceptionally(ex -> {
                    System.out.println("Ошибка SQL - " + ex.getMessage());
                    return null;
                });
        }
    }

    public void handleGetKeys(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|"); // GET_BUNDLE|||email
        
        if (parts.length != 2) {
            System.out.println("Неверный Формат!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("ERROR_FORMAT\n").getBytes()));
            return;
        }

        String email = parts[1];

        PostgresUtils.getKeys(email)
            .thenAccept(rs -> {
                System.out.println("Ключи найдены, отправка клиенту: " + rs);
                
                // Переносим отправку в поток Netty (EventLoop)
                ctx.executor().execute(() -> {
                    String response = rs + "\n";
                    ctx.writeAndFlush(Unpooled.copiedBuffer(response.getBytes()));
                });
            })
            .exceptionally(ex -> {
                System.out.println("Ошибка БД --- " + ex);
                
                // Желательно сообщить клиенту об ошибке, чтобы он не завис навсегда
                ctx.executor().execute(() -> {
                    ctx.writeAndFlush(Unpooled.copiedBuffer("ERROR_DB\n".getBytes()));
                });
                return null;
            });
    }


    public void handlePutKeys(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|");
        //PUT_KEYS|||cleanPublicIdentity|||cleanPublicX25519|||signedPreKeyId|||cleanPreKeyPublic|||cleanSignature|||passport|||email|||allPublic

        if (parts.length != 9) {
            System.out.println("Неверный Формат!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("ERROR_FORMAT" + "\n").getBytes()));
            return;
        }

        // 1. Смотрим, что вообще прилетело в Netty по сети
        System.out.println("ПОЛНАЯ СТРОКА НА СЕРВЕРЕ: [" + text + "]");

        // 2. Смотрим, как именно сплит нарезал массив
        for (int i = 0; i < parts.length; i++) {
            System.out.println("parts[" + i + "] = [" + parts[i] + "]");
        }

        // Переводим текст обратно в нормальный byte[]
        byte[] PublicIdentity = java.util.Base64.getDecoder().decode(parts[1].trim());
        byte[] fullPublicX25519 = java.util.Base64.getDecoder().decode(parts[2].trim());

        String passport = parts[6].trim();
        String email = parts[7].trim();

        String allPublic = parts[8].trim();

        int signedPreKeyId = Integer.parseInt(parts[3]);
        byte[] PreKeyPublic = java.util.Base64.getDecoder().decode(parts[4].trim());
        byte[] cleanSignature = java.util.Base64.getDecoder().decode(parts[5].trim());


        byte[] PublicX25519;
        if (fullPublicX25519.length == 33) {
            PublicX25519 = new byte[32];
            System.arraycopy(fullPublicX25519, 0, PublicX25519, 0, 32); 
        } else {
            PublicX25519 = fullPublicX25519;
        }

        System.out.println(PublicIdentity.length); 
        System.out.println(PublicX25519.length);

        if (PublicIdentity.length != 33) {
            System.out.println("Ключ Идентификации личности битый!");
        } 

        if (PublicX25519.length != 32) {
            System.out.println("Публичный ассиметричный ключ для обмена сообщениями битый!");
        }
 
        PostgresUtils.insert_keys(passport, PublicX25519, PublicIdentity, signedPreKeyId, PreKeyPublic, cleanSignature)
                .thenCompose(rs -> {
                    System.out.println("РЕЗУЛЬТАТ ПЕРВОГО ШАГА - " + rs);
                    if (rs.equals("OK")) {
                        return PostgresUtils.insert_one_timeKeys(allPublic, email);
                    }
                    return CompletableFuture.completedFuture(rs);
                })
                .thenAccept(finalRs -> {
                    System.out.println("РЕЗУЛЬТАТ ВТОРОГО ШАГА - " + finalRs);
                    if (finalRs.equals("OK")) {
                        if (!CTX_BY_EMAIL.containsKey(email)) {
                            CTX_BY_EMAIL.put(email, ctx.channel());
                            ctx.channel().attr(EMAIL_BY_CTX).set(email);
                        }
                        ctx.writeAndFlush(Unpooled.copiedBuffer((finalRs + "\n").getBytes()));
                    } else {
                        // Если цепочка вернула старый результат или ошибку
                        ctx.writeAndFlush(Unpooled.copiedBuffer((finalRs + "\n").getBytes()));
                    }
                })
                .exceptionally(ex -> {
                    System.out.println("Ошибка БД - " + ex.getMessage());
                    ex.printStackTrace();
                    return null;
                });
                
    }

    public void handleLogin(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|");
        //LOGIN|||passport_number|||password|||email

        if (parts.length != 4) {
            System.out.println("Неверный Формат!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("ERROR_FORMAT" + "\n").getBytes()));
            return;
        }

        String passport = parts[1];
        String password = parts[2];
        String email = parts[3];

        PostgresUtils.login(passport, password, email)
                .thenAccept(rs -> {
                    System.out.println("Результат логина в БД: " + rs);

                    if (rs.startsWith("YES")) {
                        if (!CTX_BY_EMAIL.containsKey(email)) {
                            CTX_BY_EMAIL.put(email, ctx.channel());
                            ctx.channel().attr(EMAIL_BY_CTX).set(email);
                            ctx.writeAndFlush(Unpooled.copiedBuffer((rs + "\n").getBytes()));
                            System.out.println("Выслали ответ YES");
                        } else {
                            ctx.writeAndFlush(Unpooled.copiedBuffer(("Пользователь уже в сети!" + "\n").getBytes()));
                        }
                    } else if (rs.startsWith("NEED_TO_GENERATE")) {
                        // ИСПРАВЛЕНО: Пересылаем саму команду генерации, а не ошибку сети!
                        if (!CTX_BY_EMAIL.containsKey(email)) {
                            CTX_BY_EMAIL.put(email, ctx.channel());
                            ctx.channel().attr(EMAIL_BY_CTX).set(email);
                        }
                        ctx.writeAndFlush(Unpooled.copiedBuffer((rs + "\n").getBytes()));
                        System.out.println("Выслали ответ NEED_TO_GENERATE");
                    }
                })
                .exceptionally(ex -> {
                    System.out.println("Ошибка при логине --- " + ex.getMessage());
                    ctx.writeAndFlush(Unpooled.copiedBuffer(("NO_USER" + "\n").getBytes()));
                    return null;
                });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        String email = ctx.channel().attr(EMAIL_BY_CTX).get();
        CTX_BY_EMAIL.remove(email);
        ctx.channel().attr(EMAIL_BY_CTX).remove();

        ctx.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("Error - " + cause.getMessage());
        ctx.close();
    }
}