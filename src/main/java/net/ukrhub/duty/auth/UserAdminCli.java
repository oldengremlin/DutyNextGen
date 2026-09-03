package net.ukrhub.duty.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;

/**
 * Командний режим застосунку для створення першого (бутстрап) облікового
 * запису веб-автентифікації, без підняття веб-сервера й Spring-контексту
 * (див. {@code DutyNextGenApplication.main}). Первинна ініціалізація —
 * README.md, розділ «Первинна ініціалізація».
 *
 * <p>Працює лише один раз — доки {@code users.txt} порожній чи не існує.
 * Якщо в ньому вже є хоч один користувач, команда відмовляє: усі наступні
 * облікові записи (і зміна ролі/пароля наявних) заводяться через веб,
 * {@link UserAdminController}, доступний уже цьому першому адміністратору.
 * Так навмисно — щоб не було спокуси "просто перезапустити add-user",
 * коли для цього є нормальна форма.
 *
 * <p>Створює користувача з роллю {@link Role#ADMIN} — інших ролей CLI не
 * знає, для першого облікового запису це і є єдиний сенс.
 *
 * <p>Аварійне відновлення (усі адміністратори випадково видалені, файл
 * users.txt пошкоджено тощо) — видалити/спорожнити {@code users.txt}
 * вручну (наприклад, через {@code docker exec}) і запустити цю команду
 * знову, вона знову спрацює на порожньому файлі.
 *
 * <pre>java -jar duty-nextgen.jar add-user &lt;ім'я&gt;</pre>
 */
public final class UserAdminCli {

    private UserAdminCli() {
    }

    public static void addUser(String[] args) {
        if (args.length < 2 || args[1].isBlank()) {
            System.err.println("Використання: java -jar duty-nextgen.jar add-user <ім'я>");
            System.exit(1);
            return;
        }
        String username = args[1];

        String configDir = System.getenv().getOrDefault("DUTY_CONFIG_DIR", "./config");
        Path usersFile = Path.of(configDir, UserStore.USERS_FILE_NAME);

        if (!UserStore.readUsers(usersFile).isEmpty()) {
            System.err.println(
                    "Облікові записи вже є — 'add-user' лише для першого, бутстрапного адміністратора.\n"
                    + "Заводь решту користувачів через веб: увійди наявним адміністратором і відкрий /admin/users.\n"
                    + "(Якщо це справді аварійне відновлення — спорожни " + usersFile.toAbsolutePath()
                    + " і спробуй ще раз.)");
            System.exit(1);
            return;
        }

        // Один Scanner на весь запуск: створення нового Scanner на кожен
        // виклик призводило до втрати даних (перший буферизує наперед
        // частину потоку, другий Scanner бачить уже порожній System.in).
        Scanner fallbackScanner = System.console() == null
                ? new Scanner(System.in, StandardCharsets.UTF_8)
                : null;

        char[] password = readSecret("Пароль для '" + username + "': ", fallbackScanner);
        char[] confirm = readSecret("Повторіть пароль: ", fallbackScanner);
        try {
            if (!Arrays.equals(password, confirm)) {
                System.err.println("Паролі не збігаються, нічого не збережено.");
                System.exit(1);
                return;
            }
            if (password.length == 0) {
                System.err.println("Порожній пароль не дозволений.");
                System.exit(1);
                return;
            }

            String hash = new BCryptPasswordEncoder().encode(new String(password));
            UserStore.writeUser(usersFile, username, hash, Role.ADMIN);
            System.out.println("Користувача '" + username + "' (роль: " + Role.ADMIN.displayName()
                    + ") збережено в " + usersFile.toAbsolutePath());
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirm, '\0');
        }
    }

    private static char[] readSecret(String prompt, Scanner fallbackScanner) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword(prompt);
        }
        // Немає інтерактивної консолі (наприклад, запуск не з термінала) —
        // читаємо звичайний рядок; пароль буде видно на екрані.
        System.out.print(prompt + "(увага: без консолі пароль буде видно під час вводу) ");
        System.out.flush();
        return fallbackScanner.hasNextLine() ? fallbackScanner.nextLine().toCharArray() : new char[0];
    }
}
