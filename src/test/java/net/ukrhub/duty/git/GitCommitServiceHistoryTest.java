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
}
