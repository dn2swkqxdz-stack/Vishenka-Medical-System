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
        cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
        cfg.setUsername("postgres");
        cfg.setPassword("");
        
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
        String patients_sql = """
            CREATE TABLE IF NOT EXISTS patients(
                id SERIAL,
                snils TEXT PRIMARY KEY,
                oms_number TEXT UNIQUE,
                fio TEXT,
                email TEXT,
                birth_date TEXT,
                gender TEXT,
                allergies TEXT,
                chronic_diseases TEXT,
                emergency_contact TEXT
            );
            """;

        String visit_card_sql = """
            CREATE TABLE IF NOT EXISTS visit_cards(
                id SERIAL,
                snils TEXT,
                name TEXT,
                description TEXT,
                note TEXT,
                is_importrant TEXT
            );
            """;
        
        try (
            Connection conn = ds.getConnection();
            Statement stmt = conn.createStatement();
        ) {
            stmt.execute(patients_sql);
            stmt.execute(visit_card_sql);

            System.out.println("Таблица Настроена!");
            return true;
        } catch (SQLException e) {
            System.out.println("Ошибка SQL - " + e.getMessage());
            return false;
        }
    }

    public static CompletableFuture<String> put_patients_disease_in_db(String name, String description, String note, String is_importrant, String snils) {
        String sql = """
                INSERT INTO visit_cards (snils, name, description, note, is_importrant) VALUES (?, ?, ?, ?, ?)
                """;
        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
            ) {
                pstmt.setString(1, snils);
                pstmt.setString(2, name);
                pstmt.setString(3, description);
                pstmt.setString(4, note);
                pstmt.setString(5, is_importrant);

                pstmt.executeUpdate();

                System.out.println("Болезнь пациента " + snils + " успешно вставлена!");
                return "YES";
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static CompletableFuture<String> getPatientHistory(String snils) {
        String imp_sql = """
            SELECT * FROM visit_cards WHERE snils = ? AND is_importrant = ? LIMIT 12
            """;

        StringBuilder builder = new StringBuilder();

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(imp_sql)
            ) {
                pstmt.setString(1, snils);
                pstmt.setString(2, "true");

                try (ResultSet set = pstmt.executeQuery()) {
                    while (set.next()) {
                        String name = set.getString("name");
                        String description = set.getString("description");
                        String note = set.getString("note");
                        String is_importrant = set.getString("is_importrant");

                        String card_str = "Название Болезни - " + name + ". Описание - " + description + ". Заметки - " + note + ". IMP = " + is_importrant + "|||";

                        builder.append(card_str);
                    }
                } 
                
                return snils; 
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor)
        .thenCompose(actualSnils -> {
            return get_not_imp_cards(actualSnils, builder);
        })
        .thenApply(ignored -> {
            return getFinalResult(builder); 
        })
        .handle((finalString, ex) -> {
            if (ex != null) {
                throw new RuntimeException(ex);
            }
            return finalString;
        });
    }

    public static CompletableFuture<String> check_cards(String snils) {
        String sql = """
            SELECT * FROM visit_cards WHERE snils = ? 
            """;
        
        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
            ) {
                pstmt.setString(1, snils);

                try (ResultSet set = pstmt.executeQuery()) {
                    if (set.next()) {
                        System.out.println("У пациента были болезни до этого");
                        return "HAVE_CARDS";
                    } else {
                        System.out.println("Пациент не сообщел о болезнях никогда");
                        return "DONT_HAVE_CARDS";
                    }
                } 
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static CompletableFuture<Void> get_not_imp_cards(String snils, StringBuilder builder) {
        String default_sql = "SELECT * FROM visit_cards WHERE snils = ? AND is_importrant = ? LIMIT 2";
        
        // Возвращаем CompletableFuture прямо из метода!
        return CompletableFuture.runAsync(() -> { // runAsync вместо supplyAsync, так как возвращаем Void
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(default_sql)
            ) {
                pstmt.setString(1, snils);
                pstmt.setString(2, "false");

                try (ResultSet set = pstmt.executeQuery()) {
                    while (set.next()) {
                        String name = set.getString("name");
                        String description = set.getString("description");
                        String note = set.getString("note");
                        String is_importrant = set.getString("is_importrant");

                        String card_str = "Название Болезни - " + name + ". Описание - " + description + ". Заметки - " + note + ". IMP = " + is_importrant + "|||";
                        builder.append(card_str);
                    }
                } 
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor); // Не забываем наш пул виртуальных потоков!
    }

    public static String getFinalResult(StringBuilder builder) {
        return builder.toString();
    }

    public static CompletableFuture<String> InsertPatient(String snils, String oms, String fio, String email, String birth_date, String gender, String allergies, String chronic_diseases, String emergency_contact){
        String sql = """
            INSERT INTO patients (snils, oms_number, fio, email, birth_date, gender, allergies, chronic_diseases, emergency_contact) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """; 

        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection connection = ds.getConnection();
                PreparedStatement pstmt = connection.prepareStatement(sql)
            ) {
                pstmt.setString(1, snils);
                pstmt.setString(2, oms);
                pstmt.setString(3, fio);
                pstmt.setString(4, email);
                pstmt.setString(5, birth_date);
                pstmt.setString(6, gender);
                pstmt.setString(7, allergies);
                pstmt.setString(8, chronic_diseases);
                pstmt.setString(9, emergency_contact);

                pstmt.executeUpdate(); 

                return "YES";
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }, dbExecutor);
    }

    public static CompletableFuture<String> getPatientData(String snils) {
        String sql = """
                SELECT oms_number, fio, email, birth_date, gender, allergies, chronic_diseases, emergency_contact FROM patients WHERE snils = ?
                """;
        return CompletableFuture.supplyAsync(() -> {
            try (
                Connection conn = ds.getConnection();
                PreparedStatement pstmt = conn.prepareStatement(sql)
            ) {
                pstmt.setString(1, snils);

                try (ResultSet set = pstmt.executeQuery()) {
                    if (set.next()) {
                        String oms = set.getString("oms_number");
                        String fio = set.getString("fio");
                        String email = set.getString("email");
                        String birth_date = set.getString("birth_date");
                        String gender = set.getString("gender");
                        String allergies = set.getString("allergies");
                        String chronic_diseases = set.getString("chronic_diseases");
                        String emergency_contact = set.getString("emergency_contact");

                        System.out.println(oms + "|||" + fio + "|||" + email + "|||" + birth_date + "|||" + gender + "|||" + allergies + "|||" + chronic_diseases + "|||" + emergency_contact);
                        return oms + "|||" + fio + "|||" + email + "|||" + birth_date + "|||" + gender + "|||" + allergies + "|||" + chronic_diseases + "|||" + emergency_contact;
                    } else {
                        System.out.println("Нет Пациента!");
                        return "NO";
                    }
                }
            } catch (SQLException e) {
                return "ERR";
            }
        }, dbExecutor);
    }

    public static CompletableFuture<String> IsUserExists(String snils) {
        String sql = """
            SELECT oms_number FROM patients WHERE snils = ?
            """;

        // Передаем задачу в наш изолированный пул потоков dbExecutor
        return CompletableFuture.supplyAsync(() -> {
            // Стандартный блокирующий JDBC-код, но внутри отдельного потока
            try (Connection connection = ds.getConnection();
                PreparedStatement pstmt = connection.prepareStatement(sql)) {

                pstmt.setString(1, snils);

                try (ResultSet resultSet = pstmt.executeQuery()) {
                    if (resultSet.next()) {
                        return "EXISTS";
                    } else {
                        return "NOT_EXISTS";
                    }
                }

            } catch (SQLException e) {
                System.out.println("Ошибка SQL - (isUserExistsAsync) " + e.getMessage());
                return "ERR";
            }
        }, dbExecutor); // <-- КРИТИЧЕСКИ ВАЖНО: передаем наш пул сюда
    }
}
