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
 * {@code ім'я:bcrypt-хеш:РОЛЬ}. Спільне для {@link FileUserDetailsService}
 * (веб-автентифікація), {@link UserAdminCli} (первинний бутстрап через
 * командний рядок — завжди створює {@link Role#ADMIN}) і
 * {@link UserAdminController} (керування рештою користувачів через веб).
 *
 * <p>Рядки без третього поля (записані до появи ролей) трактуються як
 * {@link Role#ADMIN} — саме такою була семантика "єдиного користувача"
 * раніше, і не хочеться мовчки понижувати права вже наявних облікових
 * записів при оновленні.
 */
final class UserStore {

    static final String USERS_FILE_NAME = "users.txt";

    private UserStore() {
    }

    record StoredUser(String passwordHash, Role role) {
    }

    static Map<String, StoredUser> readUsers(Path usersFile) {
        if (!Files.exists(usersFile)) {
            return Map.of();
        }
        try {
            Map<String, StoredUser> result = new LinkedHashMap<>();
            for (String line : Files.readAllLines(usersFile, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(":", 3);
                if (parts.length < 2) {
                    continue;
                }
                Role role = parts.length == 3 ? Role.valueOf(parts[2]) : Role.ADMIN;
                result.put(parts[0], new StoredUser(parts[1], role));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + usersFile, e);
        }
    }

    static void writeUser(Path usersFile, String username, String bcryptHash, Role role) {
        Map<String, StoredUser> users = new LinkedHashMap<>(readUsers(usersFile));
        users.put(username, new StoredUser(bcryptHash, role));
        save(usersFile, users);
    }

    static void deleteUser(Path usersFile, String username) {
        Map<String, StoredUser> users = new LinkedHashMap<>(readUsers(usersFile));
        users.remove(username);
        save(usersFile, users);
    }

    private static void save(Path usersFile, Map<String, StoredUser> users) {
        try {
            if (usersFile.getParent() != null) {
                Files.createDirectories(usersFile.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# duty-nextgen: облікові записи веб-автентифікації (ім'я:bcrypt-хеш:роль)\n");
            for (var entry : users.entrySet()) {
                sb.append(entry.getKey()).append(':')
                        .append(entry.getValue().passwordHash()).append(':')
                        .append(entry.getValue().role().name()).append('\n');
            }
            Files.writeString(usersFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + usersFile, e);
        }
    }
}
