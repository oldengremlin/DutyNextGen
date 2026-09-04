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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>Файл містить bcrypt-хеші паролів, тож при кожному записі йому
 * примусово виставляються права {@code rw-------}: інакше він лягає з
 * поточним umask (типово {@code rw-r--r--}) і хеші видно будь-якому
 * процесу в контейнері чи будь-кому, хто має доступ до змонтованого
 * тому {@code /config}. На файлових системах без POSIX-прав
 * (не Linux) крок тихо пропускається — там немає що виставляти.
 */
final class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    static final String USERS_FILE_NAME = "users.txt";

    /** Роздільник полів у {@code users.txt} — саме він і задає, що не можна писати в поле. */
    private static final char FIELD_SEPARATOR = ':';

    /** Лише власник читає й пише — файл із хешами паролів не для чужих очей. */
    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private UserStore() {
    }

    record StoredUser(String passwordHash, Role role, String linkedEngineer) {
    }

    /**
     * Ім'я користувача чи прив'язка інженера, придатні до запису — без
     * роздільника полів і без символів переводу рядка. Без цієї перевірки
     * ім'я на кшталт {@code "х:$2a$..:ADMIN"} записалось би одним рядком і
     * при наступному читанні розпалось на чужі поля: підвищення прав через
     * форму, яку інакше видно лише адміністратору, але яку той міг би
     * заповнити й з чужої підказки.
     *
     * @throws IllegalArgumentException якщо значення непридатне до запису
     */
    static void requireStorable(String value, String fieldLabel) {
        if (value == null) {
            return;
        }
        if (value.indexOf(FIELD_SEPARATOR) >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    fieldLabel + " не може містити символ «" + FIELD_SEPARATOR + "» чи перенесення рядка");
        }
    }

    /**
     * Усі облікові записи з файлу, у порядку рядків. Порожній файл, як і
     * його відсутність — порожня мапа (жоден вхід неможливий, доки
     * адміністратор не створить першого користувача).
     */
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
                Role role = roleOf(parts, usersFile);
                String linkedEngineer = parts.length == 4 && !parts[3].isBlank() ? parts[3] : null;
                result.put(parts[0], new StoredUser(parts[1], role, linkedEngineer));
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + usersFile, e);
        }
    }

    /**
     * Невідома роль у файлі (ручне редагування з помилкою, пошкоджений
     * запис) не має валити весь вхід у застосунок — понижуємо такий рядок
     * до найменш привілейованої ролі й голосно попереджаємо. Раніше
     * {@code Role.valueOf} кидав виняток просто з парсера, і один битий
     * рядок робив недоступною автентифікацію для ВСІХ.
     */
    private static Role roleOf(String[] parts, Path usersFile) {
        if (parts.length < 3 || parts[2].isBlank()) {
            // Рядок, записаний до появи ролей — історично це був єдиний,
            // повноправний користувач; мовчки понижувати його не можна.
            return Role.ADMIN;
        }
        try {
            return Role.valueOf(parts[2]);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown role \"{}\" for user \"{}\" in {} — falling back to {}",
                    parts[2], parts[0], usersFile, Role.VIEWER);
            return Role.VIEWER;
        }
    }

    /** Записує користувача, зберігаючи наявну прив'язку до інженера (якщо була). */
    static void writeUser(Path usersFile, String username, String bcryptHash, Role role) {
        writeUser(usersFile, username, bcryptHash, role, currentLink(usersFile, username));
    }

    /** Створює або повністю перезаписує запис користувача, разом із прив'язкою до інженера. */
    static void writeUser(Path usersFile, String username, String bcryptHash, Role role, String linkedEngineer) {
        requireStorable(username, "Ім'я користувача");
        requireStorable(linkedEngineer, "Ім'я прив'язаного інженера");
        Map<String, StoredUser> users = new LinkedHashMap<>(readUsers(usersFile));
        users.put(username, new StoredUser(bcryptHash, role, linkedEngineer));
        save(usersFile, users);
    }

    /** Прибирає користувача з файлу. Відсутній користувач — не помилка. */
    static void deleteUser(Path usersFile, String username) {
        Map<String, StoredUser> users = new LinkedHashMap<>(readUsers(usersFile));
        users.remove(username);
        save(usersFile, users);
    }

    /** Прив'язка, яку користувач має зараз — щоб зміна ролі чи пароля її не стерла. */
    private static String currentLink(Path usersFile, String username) {
        StoredUser existing = readUsers(usersFile).get(username);
        return existing != null ? existing.linkedEngineer() : null;
    }

    /** Переписує файл цілком (єдиний спосіб запису) і одразу звужує права до {@link #OWNER_ONLY}. */
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
            restrictToOwner(usersFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + usersFile, e);
        }
    }

    /**
     * Права {@code rw-------} на файл із хешами паролів. Файлова система
     * без POSIX-прав ({@code UnsupportedOperationException}) — не помилка:
     * там просто нема чого звужувати, а валити через це збереження
     * користувача було б гірше, ніж лишити файл як є.
     */
    private static void restrictToOwner(Path usersFile) {
        try {
            Files.setPosixFilePermissions(usersFile, OWNER_ONLY);
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX file permissions are not supported for {} — leaving as is", usersFile);
        } catch (IOException e) {
            log.warn("Failed to restrict permissions of {} to owner-only: {}", usersFile, e.getMessage());
        }
    }
}
