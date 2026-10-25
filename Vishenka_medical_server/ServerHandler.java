import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;

@Sharable
public class ServerHandler extends ChannelInboundHandlerAdapter {
    static ChannelGroup allClients = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private static final AttributeKey<String> STATE = AttributeKey.valueOf("state");
    private static final AttributeKey<String> SNILS_BY_CTX = AttributeKey.valueOf("snils");

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        allClients.add(ctx.channel()); // adding new client 
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Теперь msg — это гарантированно одна чистая строка String благодаря LineBasedFrameDecoder
        String text = ((String) msg).trim(); 

        if (text.startsWith("PATIENT_OPTION")) {
            handlePatientOption(ctx, text);
        } else if (text.startsWith("ANALYZE_CURRENT_PATIENT")) {
            String state = ctx.channel().attr(STATE).get();
            if (state == null) {
                System.out.println("Выполняется диагностика пациента...");
                ctx.writeAndFlush(Unpooled.copiedBuffer(("ANALYZING_STARTED" + "\n").getBytes()));
                
                handleAnalyze(ctx, text);
            } else if (state.equals("WAITING_FINAL_PULL")) {
                System.out.println("Фиксируем болезнь пациента в БД...");

                put_patients_disease_in_db(ctx, text);
            }
        } else {
            System.out.println("Неизвестная Команда!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("NO_UNKNOW_CMD" + "\n").getBytes()));
        }
    }

    public void put_patients_disease_in_db(ChannelHandlerContext ctx, String text) {
        String[] parts = text.split("\\|\\|\\|");
        if (parts.length != 5) {
            System.out.println("Неверный Формат Команды!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("INVALID_CMD_FORMAT" + "\n").getBytes()));
        }

        String name = parts[1];
        String description = parts[2];
        String note = parts[3];
        String is_importrant = parts[4];
        String snils = ctx.channel().attr(SNILS_BY_CTX).get();

        PostgresUtils.put_patients_disease_in_db(name, description, note, is_importrant, snils)
                .thenAccept(rs -> {
                    System.out.println(rs);
                    ctx.writeAndFlush(Unpooled.copiedBuffer(("YES" + "\n").getBytes()));
                    ctx.channel().attr(STATE).set(null);
                })
                .exceptionally(ex -> {
                    System.out.println("Ошибка SQL - " + ex.getMessage());
                    return null;
                });
    }

    public void handleAnalyze(ChannelHandlerContext ctx, String text) {
        String snils = ctx.channel().attr(SNILS_BY_CTX).get();

        String[] parts = text.split("\\|\\|\\|");
        if (parts.length != 2) {
            System.out.println("Неверный Формат Команды!");
            ctx.writeAndFlush(Unpooled.copiedBuffer(("INVALID_CMD_FORMAT" + "\n").getBytes()));
            return;
        }

        PostgresUtils.check_cards(snils)
            .thenAccept(rs -> {
                if (rs.equals("HAVE_CARDS")) {
                    //какое то действие если карточки есть (например, их сбор и более точное обыследование)
                    PostgresUtils.getPatientHistory(snils)
                            .thenAccept(res -> {
                                String prompt = parts[1] + "||| КАРТОЧКИ ПРОШЛЫХ БОЛЕЗНЕЙ " + res; 
                                System.out.println(prompt);

                                OllamaClient.send(prompt)
                                    .thenAccept(result -> {
                                        System.out.println(result);
                                        ctx.writeAndFlush(Unpooled.copiedBuffer((result + "\n").getBytes()));
                                        ctx.channel().attr(STATE).set("WAITING_FINAL_PULL");
                                    })
                                    .exceptionally(ex -> {
                                        System.out.println("Ошибка" + ex.getMessage());
                                        return null;
                                    });
                            })
                            .exceptionally(ex -> {
                                System.out.println("Ошибка" + ex.getMessage());
                                return null;                                
                            });
                } else {
                    String prompt = parts[1] + "|||ND"; 
                    OllamaClient.send(prompt)
                        .thenAccept(res -> {
                            System.out.println(res);
                            ctx.writeAndFlush(Unpooled.copiedBuffer((res + "\n").getBytes()));
                            ctx.channel().attr(STATE).set("WAITING_FINAL_PULL");
                        })
                        .exceptionally(ex -> {
                            System.out.println("Ошибка" + ex.getMessage());
                            return null;
                        });
                }
            })
            .exceptionally(ex -> {
                return null;
            });
    }

    public void handlePatientOption (ChannelHandlerContext ctx, String text) {
        String state = ctx.channel().attr(STATE).get();

        if (state == null) {
            String[] parts = text.split("\\|\\|\\|");
            if (parts.length != 2) {
                System.out.println("Неверный Формат Команды!");
                ctx.writeAndFlush(Unpooled.copiedBuffer(("INVALID_CMD_FORMAT" + "\n").getBytes()));
                return;
            }

            String result = checkSnils(parts[1]);

            if (result.equals("SNILS_OK")) {
                System.out.println("Снилс Верный!");

                PostgresUtils.IsUserExists(parts[1])
                    .thenAccept(isPatientReg -> {
                        if (isPatientReg.equals("EXISTS")) {
                            System.out.println("Пациент Уже Зарегистрирован!");

                            //Достаем данные пациента
                            PostgresUtils.getPatientData(parts[1])
                                .thenAccept(rs -> {
                                    if (rs.equals("NO")) {
                                        ctx.writeAndFlush(Unpooled.copiedBuffer(("NO" + "\n").getBytes()));
                                    } else if (rs.equals("ERR")) {
                                        ctx.writeAndFlush(Unpooled.copiedBuffer(("ERR" + "\n").getBytes()));
                                    } else {
                                        ctx.writeAndFlush(Unpooled.copiedBuffer(("GET_CURRENT_PATIENT---" + rs + "\n").getBytes()));
                                        ctx.channel().attr(SNILS_BY_CTX).set(parts[1]);
                                    }
                                })
                                .exceptionally(ex -> {
                                    System.out.println("Не удалось получить данные: " + ex.getCause().getMessage());
                                    return null;
                                });

                        } else if (isPatientReg.equals("NOT_EXISTS")) {
                            System.out.println("Пациент Незарегистрирован! Ждем данных для создания");
                            ctx.channel().attr(STATE).set("REGISTER_DATA_WAITING");
                        } else if (isPatientReg.equals("ERR")) {
                            System.out.println("Ошибка!");
                            ctx.writeAndFlush(Unpooled.copiedBuffer(("SERVER_ERR" + "\n").getBytes()));
                        }
                    })
                    .exceptionally(ex -> {
                        // Сюда мы прилетим, если, например, нарушилась уникальность UNIQUE (дубликат СНИЛС)
                        System.out.println("Не удалось сохранить данные: " + ex.getCause().getMessage());
                        ctx.writeAndFlush(Unpooled.copiedBuffer(("NO_DUPLICATE" + "\n").getBytes()));
                        // Здесь отправляем клиенту ошибку: ctx.writeAndFlush("ERROR");
                        return null;
                    });
                
            } else if (result.equals("INVALID_SNILS")) {
                System.out.println("Снилс Невалиден!");
                ctx.writeAndFlush(Unpooled.copiedBuffer(("INVALID_SNILS" + "\n").getBytes()));
            } else {
                System.out.println("Неизвестный Ответ!");
                ctx.writeAndFlush(Unpooled.copiedBuffer(("NO_SERVER_ERR" + "\n").getBytes()));
            }
        } else if (state.equals("REGISTER_DATA_WAITING")) {
            System.out.println("Пришли данные для регистрации пациента");

            String[] parts = text.split("\\|\\|\\|");
            if (parts.length != 10) {
                System.out.println("Неверный Формат");
                ctx.writeAndFlush(Unpooled.copiedBuffer(("INVALID_CMD_FORMAT" + "\n").getBytes()));
            }

            String snils = parts[1];
            String oms = parts[2];
            String fio = parts[3];
            String email = parts[4];
            String birth_date = parts[5];
            String gender = parts[6];
            String allergies = parts[7];
            String chronic_diseases = parts[8];
            String emergency_contact = parts[9];

            // Запускаем асинхронную вставку в базу
            PostgresUtils.InsertPatient(snils, oms, fio, email, birth_date, gender, allergies, chronic_diseases, emergency_contact)
                .thenAccept(rs -> {
                    if (rs.equals("YES")) {
                        System.out.println("Вставили");
                        ctx.channel().attr(STATE).set(null);
                        ctx.channel().attr(SNILS_BY_CTX).set(snils);
                        ctx.writeAndFlush(Unpooled.copiedBuffer(("YES" + "\n").getBytes()));
                    }
                })
                .exceptionally(ex -> {
                    // Сюда мы прилетим, если, например, нарушилась уникальность UNIQUE (дубликат СНИЛС)
                    System.out.println("Не удалось сохранить данные: " + ex.getCause().getMessage());
                    // Здесь отправляем клиенту ошибку: ctx.writeAndFlush("ERROR");
                    return null;
                });
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        allClients.remove(ctx.channel());
        ctx.close();
    }

    private String checkSnils(String full_snils) {
        String[] parts_snils = full_snils.split(" ");

        int control_num = Integer.parseInt(parts_snils[1]);
        String control_num_str = parts_snils[1];

        char[] clear_snils = parts_snils[0].replaceAll("\\D", "").toCharArray();

        if (clear_snils.length != 9) {
            System.out.println("Ошибка, СНИЛС невалиден");
            return "INVALID_SNILS";
        }

        System.out.println("Длина: " + clear_snils.length);
        System.out.println("Содержимое: " + java.util.Arrays.toString(clear_snils));

        int pos = 9;
        int result = 0;
        for (char num : clear_snils) {
            int int_num = num - '0';
            int_num = int_num * pos;
            pos--;
            result = result + int_num;
        }

        System.out.println("Результат " + result);
        String result_str = "none";

        if (result < 100 && result == control_num) {
            System.out.println("СНИЛС правилен!");
            return "SNILS_OK";
        } else if (result == 100 || result == 101) {
            if (control_num_str.equals("00")) {
                System.out.println("СНИЛС правилен!");
                return "SNILS_OK";
            }
        } else if (result > 101) {
            result = result % 101;
            System.out.println(result);

            if (result == 100 || result == 101) {
                result_str = "00";
            }

            if (!result_str.equals("none")) {
                if (result_str.equals(control_num_str)) {
                    System.out.println("СНИЛС правилен!");
                    return "SNILS_OK";
                } else {
                    System.out.println("СНИЛС НЕправилен!");
                    return "INVALID_SNILS";
                }
            } else {
                if (result == control_num) {
                    System.out.println("СНИЛС правилен!");
                    return "SNILS_OK";
                } else {
                    System.out.println("СНИЛС НЕправилен!");
                    return "INVALID_SNILS";
                }
            }
        } else {
            System.out.println("СНИЛС НЕправилен!");
            return "INVALID_SNILS";
        }
        return "INVALID_SNILS";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("Error - " + cause.getMessage());
        ctx.close();
    }
}