package net.ukrhub.duty.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Читання й запис {@code users.txt} — рядок на користувача:
 * {@code ім'я:bcrypt-хеш}. Спільне для {@link FileUserDetailsService}
 * (веб-автентифікація) і {@link UserAdminCli} (створення/зміна пароля
 * через командний рядок).
 */
final class UserStore {

    static final String USERS_FILE_NAME = "users.txt";

    private UserStore() {
    }

    static Map<String, String> readUsers(Path usersFile) {
        if (!Files.exists(usersFile)) {
            return Map.of();
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int idx = trimmed.indexOf(':');
                if (idx < 0) {
                    continue;
                }
                result.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + usersFile, e);
        }
    }

    static void writeUser(Path usersFile, String username, String bcryptHash) {
        Map<String, String> users = new LinkedHashMap<>(readUsers(usersFile));
        users.put(username, bcryptHash);
        try {
            if (usersFile.getParent() != null) {
                Files.createDirectories(usersFile.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# duty-nextgen: облікові записи веб-автентифікації (ім'я:bcrypt-хеш)\n");
            for (var entry : users.entrySet()) {
                sb.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
            }
            Files.writeString(usersFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + usersFile, e);
        }
    }
}
