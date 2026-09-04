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
package net.ukrhub.duty.git;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GitCommitServiceHistoryTest {

    @Test
    void historyListsCommitsNewestFirstWithDiffs(@TempDir Path tempDir) throws IOException {
        GitCommitService service = new GitCommitService();
        Path dataDir = tempDir.resolve("data");
        Path file = dataDir.resolve("203401");

        Files.createDirectories(dataDir);
        Files.writeString(file, "перша версія\n", StandardCharsets.UTF_8);
        service.commit(dataDir, file, "перший коміт", "Марченко І.", "marchenko@example.com");

        Files.writeString(file, "друга версія\n", StandardCharsets.UTF_8);
        service.commit(dataDir, file, "другий коміт", "Кулинич А.", "kulynych@example.com");

        List<CommitInfo> history = service.history(dataDir, file);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).message()).isEqualTo("другий коміт");
        assertThat(history.get(0).author()).isEqualTo("Кулинич А.");
        assertThat(history.get(0).diff()).contains("+друга версія").contains("-перша версія");

        assertThat(history.get(1).message()).isEqualTo("перший коміт");
        assertThat(history.get(1).author()).isEqualTo("Марченко І.");
        assertThat(history.get(1).diff()).contains("+перша версія");
    }

    /**
     * Реальний випадок на production: збереження шаблону ротації падало
     * з 500 (git commit завершувався кодом 1, "nothing added to commit
     * but untracked files present"), хоча файл на диску коректно
     * записувався — записаний вміст побайтово збігався з уже
     * закомiченим (повторне збереження без реальних змін). Це має бути
     * нешкідливим no-op, а не винятком.
     */
    @Test
    void committingUnchangedContentIsNoopNotException(@TempDir Path tempDir) throws IOException {
        GitCommitService service = new GitCommitService();
        Path dataDir = tempDir.resolve("data");
        Path file = dataDir.resolve("203401");

        Files.createDirectories(dataDir);
        Files.writeString(file, "той самий вміст\n", StandardCharsets.UTF_8);
        service.commit(dataDir, file, "перший коміт", "Марченко І.", "marchenko@example.com");

        // Той самий вміст записано ще раз (наприклад, повторне
        // "Зберегти" без реальних змін) — git тут не має чого комітити.
        Files.writeString(file, "той самий вміст\n", StandardCharsets.UTF_8);
        service.commit(dataDir, file, "другий коміт (без реальних змін)", "Марченко І.", "marchenko@example.com");

        assertThat(service.history(dataDir, file)).hasSize(1);
    }
}
