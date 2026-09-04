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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CalDavSyncStateTest {

    @Test
    void readingMissingFileReturnsEmptyMap(@TempDir Path tempDir) {
        assertThat(CalDavSyncState.read(tempDir, YearMonth.of(2033, 3))).isEmpty();
    }

    @Test
    void writeThenReadRoundTrips(@TempDir Path tempDir) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put("duty-20330301-1@duty.ukrhub.net", "hash1");
        state.put("duty-20330302-2@duty.ukrhub.net", "hash2");

        CalDavSyncState.write(tempDir, YearMonth.of(2033, 3), state);
        Map<String, String> read = CalDavSyncState.read(tempDir, YearMonth.of(2033, 3));

        assertThat(read).containsExactlyEntriesOf(state);
    }

    @Test
    void differentMonthsHaveIndependentState(@TempDir Path tempDir) {
        CalDavSyncState.write(tempDir, YearMonth.of(2033, 3), Map.of("a", "1"));
        CalDavSyncState.write(tempDir, YearMonth.of(2033, 4), Map.of("b", "2"));

        assertThat(CalDavSyncState.read(tempDir, YearMonth.of(2033, 3))).containsExactly(Map.entry("a", "1"));
        assertThat(CalDavSyncState.read(tempDir, YearMonth.of(2033, 4))).containsExactly(Map.entry("b", "2"));
    }

    @Test
    void writeOverwritesPreviousState(@TempDir Path tempDir) {
        CalDavSyncState.write(tempDir, YearMonth.of(2033, 3), Map.of("old", "1"));
        CalDavSyncState.write(tempDir, YearMonth.of(2033, 3), Map.of("new", "2"));

        assertThat(CalDavSyncState.read(tempDir, YearMonth.of(2033, 3))).containsExactly(Map.entry("new", "2"));
    }
}
