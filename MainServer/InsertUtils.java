import java.util.Scanner;
import java.util.UUID;

public class InsertUtils {

    public static final Scanner scanner = new Scanner(System.in);
    public static final MainServer server = new MainServer();

    public static String currentKey = "";
    public static String currentHost = "";

    public static void main(String[] args) {
        System.out.println("Vishenka.Med Admin Reactive Console");
        while (true) { 
            System.out.print("vmedsh> ");
            String cmd = scanner.nextLine();

            if (cmd.startsWith("exec")) {
                if (cmd.contains("insert local messenger")) {
                    System.out.print("PASSWORD: ");
                    String pass = scanner.nextLine();

                    if (!pass.equalsIgnoreCase("197826asd")) {
                        System.out.println("Access denied");
                    } else {
                        System.out.println("Successful login");
                        start_insert_mode();
                    }
                } else {
                    System.out.println("no executable command for " + cmd);
                }
            }
        }
    }

    private static void start_insert_mode() {
        while (true) { 
            System.out.print("vmedsh_insert> ");
            String cmd = scanner.nextLine();

            if (cmd.equalsIgnoreCase("gen+host")) {
                UUID_gen();
                System.out.println("Successful generated");

                System.out.print("Enter Host: ");
                currentHost = scanner.nextLine();
            } else if (cmd.equalsIgnoreCase("/exit")) {
                return;
            } else if (cmd.equalsIgnoreCase("pushing insert")) {
                if (PostgresUtils.insert_messenger_server(currentKey, currentHost)) {
                    System.out.println("Successful Inserted");
                } else {
                    System.out.println("Error");
                }

                return;
            }
        }
    }

    private static void UUID_gen() {
        currentKey = UUID.randomUUID().toString().substring(9);
    }
}
