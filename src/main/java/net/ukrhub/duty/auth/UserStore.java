/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * {@code ім'я:bcrypt-хеш:РОЛЬ:прив'язаний_інженер}. Спільне для
 * {@link FileUserDetailsService} (веб-автентифікація), {@link UserAdminCli}
 * (первинний бутстрап через командний рядок — завжди створює
 * {@link Role#ADMIN}, без прив'язки) і {@link UserAdminController}
 * (керування рештою користувачів через веб).
 *
 * <p>Рядки без третього поля (записані до появи ролей) трактуються як
 * {@link Role#ADMIN} — саме такою була семантика "єдиного користувача"
 * раніше, і не хочеться мовчки понижувати права вже наявних облікових
 * записів при оновленні. Четверте поле (прив'язка) — необов'язкове й
 * може бути порожнім навіть у нових рядках.
 *
 * <p>Прив'язка — за іменем інженера (не за номером у місячному файлі:
 * номер стабільний лише в межах одного місяця й переприсвоюється при
 * зміні ростеру). Це свідомо крихкий зв'язок, який ламається при
 * перейменуванні — саме тому {@code ScheduleEditController} при
 * перейменуванні інженера адміністратором переносить прив'язку на нове
 * ім'я через {@link UserLinkService}.
 */
final class UserStore {

    static final String USERS_FILE_NAME = "users.txt";

    private UserStore() {
    }

    record StoredUser(String passwordHash, Role role, String linkedEngineer) {
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
                String[] parts = trimmed.split(":", 4);
                if (parts.length < 2) {
                    continue;
                }
                Role role = parts.length >= 3 && !parts[2].isBlank() ? Role.valueOf(parts[2]) : Role.ADMIN;
                String linkedEngineer = parts.length == 4 && !parts[3].isBlank() ? parts[3] : null;
                result.put(parts[0], new StoredUser(parts[1], role, linkedEngineer));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + usersFile, e);
        }
    }

    static void writeUser(Path usersFile, String username, String bcryptHash, Role role) {
        writeUser(usersFile, username, bcryptHash, role, currentLink(usersFile, username));
    }

    static void writeUser(Path usersFile, String username, String bcryptHash, Role role, String linkedEngineer) {
        Map<String, StoredUser> users = new LinkedHashMap<>(readUsers(usersFile));
        users.put(username, new StoredUser(bcryptHash, role, linkedEngineer));
        save(usersFile, users);
    }

    static void deleteUser(Path usersFile, String username) {
        Map<String, StoredUser> users = new LinkedHashMap<>(readUsers(usersFile));
        users.remove(username);
        save(usersFile, users);
    }

    private static String currentLink(Path usersFile, String username) {
        StoredUser existing = readUsers(usersFile).get(username);
        return existing != null ? existing.linkedEngineer() : null;
    }

    private static void save(Path usersFile, Map<String, StoredUser> users) {
        try {
            if (usersFile.getParent() != null) {
                Files.createDirectories(usersFile.getParent());
            }
            StringBuilder sb = new StringBuilder();
            sb.append("# duty-nextgen: облікові записи веб-автентифікації (ім'я:bcrypt-хеш:роль:прив'язаний_інженер)\n");
            for (var entry : users.entrySet()) {
                StoredUser u = entry.getValue();
                sb.append(entry.getKey()).append(':')
                        .append(u.passwordHash()).append(':')
                        .append(u.role().name()).append(':')
                        .append(u.linkedEngineer() != null ? u.linkedEngineer() : "")
                        .append('\n');
            }
            Files.writeString(usersFile, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + usersFile, e);
        }
    }
}
