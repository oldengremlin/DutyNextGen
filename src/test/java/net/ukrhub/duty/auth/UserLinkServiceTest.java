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
