package net.ukrhub.duty.auth;

import net.ukrhub.duty.config.DutyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UserLinkServiceTest {

    private static UserLinkService serviceFor(Path tempDir) {
        return new UserLinkService(new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.toString(), null, tempDir.resolve("templates").toString(),
                tempDir.resolve("exchanges").toString()));
    }

    @Test
    void renamePropagatesToLinkedUser(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve(UserStore.USERS_FILE_NAME);
        UserStore.writeUser(usersFile, "someone", "hash", Role.VIEWER, "Леонов О.");

        serviceFor(tempDir).renameEngineer("Леонов О.", "Лєонов О.");

        assertThat(UserStore.readUsers(usersFile).get("someone").linkedEngineer()).isEqualTo("Лєонов О.");
    }

    @Test
    void renameDoesNotTouchUnrelatedUsers(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve(UserStore.USERS_FILE_NAME);
        UserStore.writeUser(usersFile, "someone", "hash", Role.VIEWER, "Інший І.");

        serviceFor(tempDir).renameEngineer("Леонов О.", "Лєонов О.");

        assertThat(UserStore.readUsers(usersFile).get("someone").linkedEngineer()).isEqualTo("Інший І.");
    }

    @Test
    void renameOfUnlinkedNameIsNoop(@TempDir Path tempDir) {
        Path usersFile = tempDir.resolve(UserStore.USERS_FILE_NAME);
        UserStore.writeUser(usersFile, "someone", "hash", Role.VIEWER);

        serviceFor(tempDir).renameEngineer("Леонов О.", "Лєонов О.");

        assertThat(UserStore.readUsers(usersFile).get("someone").linkedEngineer()).isNull();
    }
}
