package net.ukrhub.duty.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
