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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class UserStoreTest {

    @Test
    void roundTripsUsernameHashAndRole(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve("users.txt");

        UserStore.writeUser(usersFile, "noc", "hash1", Role.EDITOR);
        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get("noc");

        assertThat(stored.passwordHash()).isEqualTo("hash1");
        assertThat(stored.role()).isEqualTo(Role.EDITOR);
    }

    /**
     * Регресія на реальний випадок: користувач "noc" вже був заведений
     * до появи ролей, коли users.txt писався у форматі "ім'я:хеш" (без
     * третього поля). Такий рядок не повинен мовчки понижувати права —
     * трактуємо як ADMIN, як і було семантично раніше (єдиний
     * користувач = єдиний, хто взагалі міг щось редагувати).
     */
    @Test
    void legacyTwoFieldLineDefaultsToAdmin(@TempDir Path tempDir) throws IOException {
        Path usersFile = tempDir.resolve("users.txt");
        Files.writeString(usersFile, "noc:$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ12\n",
                StandardCharsets.UTF_8);

        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get("noc");

        assertThat(stored.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void roundTripsLinkedEngineer(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve("users.txt");

        UserStore.writeUser(usersFile, "chergovyi", "hash1", Role.VIEWER, "Іванов І.");
        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get("chergovyi");

        assertThat(stored.linkedEngineer()).isEqualTo("Іванов І.");
    }

    @Test
    void writeUserWithoutLinkPreservesExistingLink(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve("users.txt");
        UserStore.writeUser(usersFile, "chergovyi", "hash1", Role.VIEWER, "Іванов І.");

        // Виклик 4-аргументної версії (як у change-role/reset-password) не
        // повинен мовчки стирати вже наявну прив'язку.
        UserStore.writeUser(usersFile, "chergovyi", "hash2", Role.EDITOR);

        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get("chergovyi");
        assertThat(stored.passwordHash()).isEqualTo("hash2");
        assertThat(stored.role()).isEqualTo(Role.EDITOR);
        assertThat(stored.linkedEngineer()).isEqualTo("Іванов І.");
    }

    @Test
    void threeFieldLegacyLineHasNoLink(@TempDir Path tempDir) throws IOException {
        Path usersFile = tempDir.resolve("users.txt");
        Files.writeString(usersFile, "noc:$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ12:EDITOR\n",
                StandardCharsets.UTF_8);

        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get("noc");

        assertThat(stored.role()).isEqualTo(Role.EDITOR);
        assertThat(stored.linkedEngineer()).isNull();
    }

    @Test
    void deleteUserRemovesOnlyThatEntry(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve("users.txt");
        UserStore.writeUser(usersFile, "keep", "hash-keep", Role.VIEWER);
        UserStore.writeUser(usersFile, "drop", "hash-drop", Role.EDITOR);

        UserStore.deleteUser(usersFile, "drop");

        var users = UserStore.readUsers(usersFile);
        assertThat(users).containsKey("keep");
        assertThat(users).doesNotContainKey("drop");
    }

    /**
     * users.txt містить bcrypt-хеші паролів — читати його має право лише
     * власник. Без явного звуження прав файл лягає з поточним umask
     * (типово rw-r--r--), тобто хеші видно будь-якому процесу в контейнері
     * й будь-кому з доступом до змонтованого тому /config.
     */
    @Test
    void usersFileIsWrittenOwnerReadableOnly(@TempDir Path tempDir) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "POSIX-права доступні не на всіх файлових системах");
        Path usersFile = tempDir.resolve("users.txt");

        UserStore.writeUser(usersFile, "noc", "hash1", Role.ADMIN);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(usersFile)))
                .isEqualTo("rw-------");
    }

    /**
     * Роздільник полів у імені перетворив би один рядок на кілька полів
     * при наступному читанні — тобто дозволив би записом "у поле імені"
     * підсунути чужий хеш і роль ADMIN. Відмовляємо на записі.
     */
    @Test
    void rejectsUsernameContainingFieldSeparator(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve("users.txt");

        assertThatThrownBy(() -> UserStore.writeUser(usersFile, "noc:hash:ADMIN", "hash1", Role.VIEWER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ім'я користувача");
        assertThat(Files.exists(usersFile)).isFalse();
    }

    @Test
    void rejectsLinkedEngineerContainingNewline(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve("users.txt");

        assertThatThrownBy(() ->
                UserStore.writeUser(usersFile, "noc", "hash1", Role.VIEWER, "Іванов І.\nfake:hash:ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Невідома роль у файлі (ручна правка з помилкою) раніше кидала
     * виняток просто з парсера — тобто один битий рядок робив
     * автентифікацію недоступною ДЛЯ ВСІХ. Тепер такий рядок понижується
     * до найменш привілейованої ролі, а решта користувачів читається як
     * завжди.
     */
    @Test
    void unknownRoleFallsBackToViewerWithoutBreakingOtherUsers(@TempDir Path tempDir) throws IOException {
        Path usersFile = tempDir.resolve("users.txt");
        Files.writeString(usersFile, "broken:hash-broken:SUPERUSER:\nnoc:hash-noc:ADMIN:\n",
                StandardCharsets.UTF_8);

        var users = UserStore.readUsers(usersFile);

        assertThat(users.get("broken").role()).isEqualTo(Role.VIEWER);
        assertThat(users.get("noc").role()).isEqualTo(Role.ADMIN);
    }
}
