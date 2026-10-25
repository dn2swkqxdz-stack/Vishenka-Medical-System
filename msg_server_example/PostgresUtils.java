import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class PostgresUtils {
    // Создаем синглтон пула соединений
    private static final HikariDataSource ds;

    public static final ExecutorService dbExecutor = Executors.newVirtualThreadPerTaskExecutor();

    static {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://localhost:5434/postgres");
        cfg.setUsername("postgres");
        cfg.setPassword("mysecretpassword");
        
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(
            0, 
            Thread.ofVirtual().factory()
        );
        cfg.setScheduledExecutor(executor);
        
        ds = new HikariDataSource(cfg);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Сервер останавливается. Закрываем пул HikariCP...");

            if (ds != null) {
                ds.close(); 
            } 
            executor.shutdown();
            
            System.out.println("Все соединения с PostgreSQL успешно закрыты.");
        }));
    }

    public static String insert_csv_users_table_if_it_is_empty() {
        if (!is_table_empty("users")) {
            return "TABLE_IS_NOT_EMPTY";
        }

        String sql = """
                INSERT INTO users (pasport_number, fio, role, email, hash, salt, Identify_public, X25519_public, signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature) 
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        List<String> users_paswords = new ArrayList<>();

        try (Connection conn = ds.getConnection();) {
            try (
                BufferedReader reader = new BufferedReader(new FileReader("UsersCSV.txt"));
                BufferedWriter writer = new BufferedWriter(new FileWriter("Passwords.txt"));

                PreparedStatement pstmt = conn.prepareStatement(sql)
            ) {
                conn.setAutoCommit(false);

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\\|\\|\\|");
                    if (parts.length != 4) {
                        System.out.println("Неверный Формат!");
                        conn.rollback();
                        return "ERROR_FORMAT";
                    }

                    String pasport_number = parts[0];
                    String fio = parts[1];
                    String role = parts[2];
                    String email = parts[3];

                    pstmt.setString(1, pasport_number);
                    pstmt.setString(2, fio);
                    pstmt.setString(3, role);
                    pstmt.setString(4, email);

                    String pass = PasswordUtils.generatePassword();

                    users_paswords.add(pasport_number + " --- " + fio + " --- " + pass);

                    String hash_and_salt = "";
                    try {
                        hash_and_salt = PasswordUtils.hashPassword(pass);
                    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
                        System.out.println("Ошибка Хеширования - " + e.getMessage());
                    }

                    String[] hash_and_salt_parts = hash_and_salt.split("\\$");

                    String salt = hash_and_salt_parts[0];
                    String hash = hash_and_salt_parts[1];

                    pstmt.setString(5, hash);
                    pstmt.setString(6, salt);

                    pstmt.setNull(7, java.sql.Types.BINARY);  // Identify_public
                    pstmt.setNull(8, java.sql.Types.BINARY);  // X25519_public

                    pstmt.setInt(9, 0);                       // signed_pre_key_id (оставляем 0)

                    pstmt.setNull(10, java.sql.Types.BINARY); // signed_pre_key_public
                    pstmt.setNull(11, java.sql.Types.BINARY); // signed_pre_key_signature

                    pstmt.executeUpdate();

                    conn.commit();
                }

                for (String password : users_paswords) {
                    writer.write(password + "\n");
                }

                users_paswords.clear();

                return "YES";
            } catch (IOException | SQLException e) {
                conn.rollback();
                System.out.println("Ошибка IO/SQL - " + e.getMessage());
                return "ERROR";
            }
        } catch (SQLException e) {
            System.out.println("Ошибка SQL - " + e.getMessage());
            return "ERROR";
        }
    }

    public static CompletableFuture<String> login(String passport, String pass, String email) {
        String sql = "SELECT hash, salt, role, fio, email FROM users WHERE pasport_number = ?";

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                pstmt.setString(1, passport);

                try (ResultSet set = pstmt.executeQuery()) {
                    if (!set.next()) {
                        // Вместо строки бросаем кастомное или готовое исключение
                        throw new IllegalArgumentException("NO_USER");
                    }

                    String DBhash = set.getString("hash");
                    String DBsalt = set.getString("salt");
                    String DBemail = set.getString("email");
                    String role = set.getString("role");
                    String fio = set.getString("fio");

                    if (!PasswordUtils.checkPass(pass, DBsalt, DBhash) || !email.equals(DBemail)) {
                        // Объединяем проверки, чтобы не выдавать злоумышленнику, что именно неверно
                        throw new IllegalArgumentException("BAD_CREDENTIALS");
                    }

                    return passport + "|||" + role + "|||" + fio;
                }
            } catch (SQLException | NoSuchAlgorithmException | InvalidKeySpecException e) {
                throw new RuntimeException(e);
            } 
        }, dbExecutor)
        // thenCompose выполнится ТОЛЬКО если предыдущий этап завершился успешно (вернул строку с данными)
        // Если вылетело исключение (NO_USER/BAD_CREDENTIALS), check_keys будет проигнорирован!
        .thenCompose(passport_role_and_fio -> check_keys(passport_role_and_fio));
    }

    public static CompletableFuture<String> insert_keys(String passport, byte[] X25519_public, byte[] Identify_public, int signed_pre_key_id, byte[] signed_pre_key_public, byte[] signed_pre_key_signature) {
        String sql = """
                UPDATE users SET X25519_public = ?, Identify_public = ?, signed_pre_key_id = ?, signed_pre_key_public = ?, signed_pre_key_signature = ? WHERE pasport_number = ?
                """;

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                pstmt.setBytes(1, X25519_public);
                pstmt.setBytes(2, Identify_public);
                pstmt.setInt(3, signed_pre_key_id);
                pstmt.setBytes(4, signed_pre_key_public);
                pstmt.setBytes(5, signed_pre_key_signature);
                pstmt.setString(6, passport);

                pstmt.executeUpdate();

                System.out.println("Обновлено");
                return "OK";
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static CompletableFuture<String> insert_one_timeKeys(String all_keys, String email) {
        // 1. Готовим SQL-запрос с ON CONFLICT
        String sql = "INSERT INTO one_time_pre_keys (user_email, key_id, key_public) " +
                    "VALUES (?, ?, ?) " +
                    "ON CONFLICT (user_email, key_id) " +
                    "DO UPDATE SET key_public = EXCLUDED.key_public";
        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                String[] parts = all_keys.split("\\*\\*\\*");

                // 2. Крутим цикл по всем 50 распарсенным ключам
                for (String part : parts) { // parts — это ключи после split("\\*\\*\\*")
                    String[] keyData = part.split(","); // бьем на [номер, ключ]
                    int keyId = Integer.parseInt(keyData[0]);
                    String keyPublic = keyData[1];

                    pstmt.setString(1, email); // email нашего врача
                    pstmt.setInt(2, keyId);        // номер ключа (например, от 1 до 50)
                    pstmt.setString(3, keyPublic); // сам Base64 ключ

                    // ВМЕСТО executeUpdate() мы просто складываем запрос в корзину (пакет)
                    pstmt.addBatch(); 
                }

                // 3. ОДНИМ УДАРОМ отправляем всю пачку из 50 ключей в DB
                pstmt.executeBatch(); 

                System.out.println("Обновлено");
                return "OK";
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static CompletableFuture<String> getKeys(String email) {
        // Явно возвращаем только key_id и key_public, отрезая SERIAL id
        String sql = """
                DELETE FROM one_time_pre_keys
                WHERE id = (
                    SELECT id FROM one_time_pre_keys WHERE user_email = ?
                    ORDER BY id ASC
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED 
                ) RETURNING key_id, key_public;
                """;

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                pstmt.setString(1, email);

                try (ResultSet set = pstmt.executeQuery()) {
                    if (set.next()) {
                        // Теперь тут ГАРАНТИРОВАННО будет число от 1 до 50
                        int keyId = set.getInt("key_id");
                        String key_public = set.getString("key_public");

                        System.out.println("=== СЕРВЕР ОТДАЕТ КЛЮЧ ===");
                        System.out.println("Реальный key_id из базы: " + keyId);
                        System.out.println("==========================");

                        return "KEYS_FOR_X3DX|||" + keyId + "***" + key_public + "|||";
                    } else {
                        System.out.println("Не найдено, используем IdentityKey и SignedPreKey");
                        return "KEY_ERROR";
                    }
                } 

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            }, dbExecutor)
            .thenCompose(one_time_pre_key -> {
                if (!one_time_pre_key.equals("KEY_ERROR")) {
                    return getIdentity_and_Signed(email, one_time_pre_key);
                } else {
                    return getIdentity_and_Signed(email, "KEYS_FOR_X2DX|||");
                }
            })
            .thenApply(rs -> {
                if ("NO_KEYS".equals(rs)) {
                    return "NO_KEYS";
                } else {
                    return rs;
                }
            })
            .handle((finalRes, ex) -> {
                if (ex != null) {
                    throw new RuntimeException(ex);
                }
                return finalRes;
            });
        }

    public static CompletableFuture<String> get_messages(String sender, String recipient) {
        String sql = """
            DELETE FROM offline_messages
            WHERE id IN (
                SELECT id FROM offline_messages 
                WHERE sender_email = ? AND recipient_email = ?
                ORDER BY id ASC
                LIMIT 100
                FOR UPDATE SKIP LOCKED 
            ) RETURNING msg_blob, msg_uuid, time, msg_type;
            """;

        return CompletableFuture.supplyAsync(() -> {
            // Используем ОДИН разделитель для всего
            java.util.StringJoiner joiner = new java.util.StringJoiner("|");

            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                pstmt.setString(1, sender);
                pstmt.setString(2, recipient);

                try (ResultSet set = pstmt.executeQuery()) {
                    while (set.next()) {
                        String msg = java.util.Base64.getEncoder().encodeToString(set.getBytes("msg_blob"));
                        String msg_uuid = set.getString("msg_uuid");
                        String time = set.getString("time");
                        String msg_type = set.getString("msg_type");

                        // Заливаем в joiner всё подряд. 
                        // Он сам вставит "|" между ВСЕМИ элементами
                        joiner.add(msg);
                        joiner.add(msg_uuid);
                        joiner.add(time);
                        joiner.add(msg_type);
                    }
                }
                
                return joiner.length() == 0 ? "NO" : joiner.toString();

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }


    public static CompletableFuture<String> getIdentity_and_Signed(String email, String one_time_pre_key) {
        String sql = """
                SELECT Identify_public, signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature FROM users WHERE email = ?
                """;

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                pstmt.setString(1, email);
                
                try (ResultSet set = pstmt.executeQuery()) {
                    // ИСПРАВЛЕННЫЙ ВАРИАНТ (для типа BYTEA):
                    if (set.next()) {
                        int signed_pre_key_id = set.getInt("signed_pre_key_id");

                        // 1. Извлекаем чистые байты напрямую из BYTEA колонок!
                        byte[] identifyBytes = set.getBytes("Identify_public");
                        byte[] signedPreKeyBytes = set.getBytes("signed_pre_key_public");
                        byte[] signatureBytes = set.getBytes("signed_pre_key_signature");

                        if (identifyBytes == null || signed_pre_key_id == 0 || 
                            signedPreKeyBytes == null || signatureBytes == null) {
                            return "KEY_ERROR";
                        }

                        // 2. Кодируем в чистый Base64 для депеши клиенту
                        String Identify_public_b64 = Base64.getEncoder().encodeToString(identifyBytes);
                        String signed_pre_key_public_b64 = Base64.getEncoder().encodeToString(signedPreKeyBytes);
                        String signed_pre_key_signature_b64 = Base64.getEncoder().encodeToString(signatureBytes);

                        return one_time_pre_key + Identify_public_b64 + "|||" + signed_pre_key_id + "***" + signed_pre_key_public_b64 + "|||" + signed_pre_key_signature_b64;
                    } else {
                        System.out.println("Пользователя нет");
                        return "NO_USER";
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static boolean is_table_empty(String table_name) {
        // Пишем прямой запрос без знаков вопроса
        String check_sql = "SELECT 1 FROM " + table_name + " LIMIT 1";

        try (
            Connection conn = ds.getConnection();
            java.sql.Statement stmt = conn.createStatement();
            ResultSet set = stmt.executeQuery(check_sql)
        ) {
            if (set.next()) {
                System.out.println("Данные в таблице " + table_name + " есть");
                return false;
            } else {
                System.out.println("Данных в таблице " + table_name + " нет");
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static CompletableFuture<String> check_keys(String passport_role_and_fio) {
        String sql = """
                SELECT Identify_public, X25519_public, signed_pre_key_id, signed_pre_key_public, signed_pre_key_signature 
                FROM users WHERE pasport_number = ?
                """;

        String[] parts = passport_role_and_fio.split("\\|\\|\\|");
        String passport_number = parts[0];

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
            ) {
                pstmt.setString(1, passport_number);

                try (ResultSet set = pstmt.executeQuery()) {
                    if (set.next()) {
                        // 1. Читаем как массивы байт, поелику у нас BYTEA
                        byte[] Identify_public = set.getBytes("Identify_public");
                        byte[] X25519_public = set.getBytes("X25519_public");
                        int signed_pre_key_id = set.getInt("signed_pre_key_id");
                        byte[] signed_pre_key_public = set.getBytes("signed_pre_key_public");
                        byte[] signed_pre_key_signature = set.getBytes("signed_pre_key_signature");

                        // 2. Проверяем: ежели хоть один узел пуст (null) или id равен 0 — заставляем генерировать!
                        if (Identify_public == null || X25519_public == null || 
                            signed_pre_key_id == 0 || signed_pre_key_public == null || 
                            signed_pre_key_signature == null) {
                            
                            return "NEED_TO_GENERATE|||" + parts[1] + "|||" + parts[2];
                        } else {
                            // Все ключи на месте, православно пускаем в сеть!
                            return "YES|||" + parts[1] + "|||" + parts[2];
                        }
                    } else {
                        return "NO_USER";
                    }
                } 
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static CompletableFuture<Void> put_offline_msg(String sender, String recipient, byte[] msg, String uuid, String time, int type) {
        String sql = """
                INSERT INTO offline_messages (sender_email, recipient_email, msg_blob, msg_uuid, time, msg_type) VALUES (?, ?, ?, ?, ?, ?)
                """;

        return CompletableFuture.runAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
            ) {
                pstmt.setString(1, sender);
                pstmt.setString(2, recipient);
                pstmt.setBytes(3, msg);
                pstmt.setString(4, uuid);
                pstmt.setString(5, time);
                pstmt.setInt(6, type);

                pstmt.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } 
        }, dbExecutor);
    }

    public static boolean createTables() {
        String users_table = """
            CREATE TABLE IF NOT EXISTS users(
                id SERIAL,
                pasport_number TEXT PRIMARY KEY NOT NULL,
                fio TEXT NOT NULL, 
                role TEXT NOT NULL,
                email TEXT NOT NULL UNIQUE,
                hash TEXT NOT NULL,
                salt TEXT NOT NULL,
                
                Identify_public BYTEA,
                X25519_public BYTEA,
                signed_pre_key_id INT NOT NULL,    
                signed_pre_key_public BYTEA,
                signed_pre_key_signature BYTEA 
            );
            """;

        String offline_messages = """
            CREATE TABLE IF NOT EXISTS offline_messages(
                id SERIAL,
                sender_email TEXT NOT NULL,
                recipient_email TEXT NOT NULL,       
                msg_blob BYTEA NOT NULL,        
                msg_uuid TEXT NOT NULL PRIMARY KEY,         
                time TEXT,
                msg_type INT NOT NULL
            );
            """;

        String one_time_pre_keys = """
            CREATE TABLE IF NOT EXISTS one_time_pre_keys(
                id SERIAL PRIMARY KEY,
                user_email TEXT NOT NULL REFERENCES users(email) ON DELETE CASCADE,
                key_id INT NOT NULL,               
                key_public TEXT NOT NULL,         
                UNIQUE(user_email, key_id)         
            );
            """;

        try (
            Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
        ) {
            stmt.execute(users_table);
            stmt.execute(offline_messages);
            stmt.execute(one_time_pre_keys);

            System.out.println("Таблица Настроена!");
            return true;
        } catch (SQLException e) {
            System.out.println("Ошибка SQL - " + e.getMessage());
            return false;
        }
    }
}
