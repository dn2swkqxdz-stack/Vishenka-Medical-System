import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
        cfg.setJdbcUrl("jdbc:postgresql://localhost:5433/postgres");
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

    public static boolean createTables() {
        String messengers = """
            CREATE TABLE IF NOT EXISTS local_messengers(
                id SERIAL,
                key TEXT PRIMARY KEY,
                host TEXT
            );
            """;
        
        try (
            Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
        ) {
            stmt.execute(messengers);

            System.out.println("Таблица Настроена!");
            return true;
        } catch (SQLException e) {
            System.out.println("Ошибка SQL - " + e.getMessage());
            return false;
        }
    }

    public static CompletableFuture<String> get_host(String key) {
        String sql = """
                SELECT host FROM local_messengers WHERE key = ?
                """;

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql);
            ) {
                pstmt.setString(1, key);

                System.out.println(key);

                try (ResultSet set = pstmt.executeQuery()) {
                    if (set.next()) {
                        String host = set.getString("host");
                        System.out.println("Нашли " + key + " по адресу " + host);
                        return "YES|||" + host;
                    } else {
                        System.out.println("Не нашли!");
                        return "NO_KEY";
                    }
                } 
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static boolean insert_messenger_server(String key, String host) {
        String sql = """
                INSERT INTO local_messengers (key, host) VALUES (?, ?)
                """;

        try (
            Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)
        ) {
            pstmt.setString(1, key);
            pstmt.setString(2, host);

            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.out.println("Ошибка SQL - " + e.getMessage());
            return false;
        }
    }
}
