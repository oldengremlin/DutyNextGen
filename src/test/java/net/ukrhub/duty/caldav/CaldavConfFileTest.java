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
package net.ukrhub.duty.caldav;

import net.ukrhub.duty.config.DutyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CaldavConfFileTest {

    @Test
    void missingFileReturnsEmpty(@TempDir Path tempDir) {
        assertThat(CaldavConfFile.readIfPresent(tempDir, "state")).isEmpty();
    }

    @Test
    void parsesRealDutyCaldavSyncFormat(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("duty-caldav.conf"), """
                # Секрети — тому цей файл НЕ кладеться в CVS/git разом з рештою
                # коду.

                CALDAV_BASE_URL="https://www.ukr-com.net:7580/dav.php/calendars/NOC/default"
                CALDAV_USER="noc"
                CALDAV_PASS="s3cr3t"

                # За замовчуванням співпадає з тим, що вже читає tds.pl
                DUTY_DIR="/data"
                STATE_DIR="/var/lib/duty-caldav"
                """, StandardCharsets.UTF_8);

        Optional<DutyProperties.Caldav> result = CaldavConfFile.readIfPresent(tempDir, "our-state-dir");

        assertThat(result).isPresent();
        DutyProperties.Caldav caldav = result.get();
        assertThat(caldav.baseUrl()).isEqualTo("https://www.ukr-com.net:7580/dav.php/calendars/NOC/default");
        assertThat(caldav.user()).isEqualTo("noc");
        assertThat(caldav.password()).isEqualTo("s3cr3t");
        // DUTY_DIR/STATE_DIR з файлу ігноруються — у nextgen свої шляхи.
        assertThat(caldav.stateDir()).isEqualTo("our-state-dir");
        assertThat(caldav.configured()).isTrue();
    }

    @Test
    void blankBaseUrlIsTreatedAsNotConfigured(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("duty-caldav.conf"), """
                CALDAV_BASE_URL=
                CALDAV_USER=
                CALDAV_PASS=
                """, StandardCharsets.UTF_8);

        assertThat(CaldavConfFile.readIfPresent(tempDir, "state")).isEmpty();
    }

    @Test
    void unquotedValuesAreAlsoAccepted(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("duty-caldav.conf"), """
                CALDAV_BASE_URL=https://example.org/cal
                CALDAV_USER=noc
                CALDAV_PASS=secret
                """, StandardCharsets.UTF_8);

        Optional<DutyProperties.Caldav> result = CaldavConfFile.readIfPresent(tempDir, "state");

        assertThat(result).isPresent();
        assertThat(result.get().baseUrl()).isEqualTo("https://example.org/cal");
    }
}
