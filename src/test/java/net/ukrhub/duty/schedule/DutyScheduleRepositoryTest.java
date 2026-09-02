package net.ukrhub.duty.schedule;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.git.GitCommitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DutyScheduleRepositoryTest {

    @Test
    void savingScheduleWritesFileAndCommitsToOwnGitRepo(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path dataDir = tempDir.resolve("duty-data");
        DutyProperties properties = new DutyProperties(dataDir.toString(), tempDir.resolve("config").toString(), null);
        DutyScheduleRepository repository = new DutyScheduleRepository(properties, new GitCommitService());

        List<Engineer> engineers = List.of(new Engineer(1, "Тестовий І.", false));
        DutySchedule schedule = new DutySchedule(
                YearMonth.of(2030, 1),
                engineers,
                List.of(new DutyDay(1, DayOfWeek.TUESDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );

        repository.save(schedule, "тестовий коміт", "Тест Тестович", "test@example.com");

        assertThat(dataDir.resolve("203001")).exists();
        assertThat(repository.find(YearMonth.of(2030, 1))).isPresent();

        Process log = new ProcessBuilder("git", "-C", dataDir.toString(), "log", "--oneline", "--format=%an|%s")
                .start();
        String output = new String(log.getInputStream().readAllBytes());
        log.waitFor();

        assertThat(output.trim()).isEqualTo("Тест Тестович|тестовий коміт");
        assertThat(dataDir.resolve(".git")).exists();
    }

    @Test
    void existsAndExistingMonthsFromFindOnlyMonthFiles(@TempDir Path tempDir) {
        Path dataDir = tempDir.resolve("duty-data");
        DutyProperties properties = new DutyProperties(dataDir.toString(), tempDir.resolve("config").toString(), null);
        DutyScheduleRepository repository = new DutyScheduleRepository(properties, new GitCommitService());

        seed(repository, YearMonth.of(2031, 1));
        seed(repository, YearMonth.of(2031, 3));
        seed(repository, YearMonth.of(2031, 4));

        assertThat(repository.exists(YearMonth.of(2031, 1))).isTrue();
        assertThat(repository.exists(YearMonth.of(2031, 2))).isFalse();
        assertThat(repository.existingMonthsFrom(YearMonth.of(2031, 2)))
                .containsExactly(YearMonth.of(2031, 3), YearMonth.of(2031, 4));
    }

    @Test
    void deleteRemovesFilesAndCommits(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path dataDir = tempDir.resolve("duty-data");
        DutyProperties properties = new DutyProperties(dataDir.toString(), tempDir.resolve("config").toString(), null);
        DutyScheduleRepository repository = new DutyScheduleRepository(properties, new GitCommitService());

        seed(repository, YearMonth.of(2031, 6));
        seed(repository, YearMonth.of(2031, 7));

        repository.delete(List.of(YearMonth.of(2031, 6), YearMonth.of(2031, 7)),
                "видалено тестові місяці", "Тест Тестович", "test@example.com");

        assertThat(dataDir.resolve("203106")).doesNotExist();
        assertThat(dataDir.resolve("203107")).doesNotExist();
        assertThat(repository.find(YearMonth.of(2031, 6))).isEmpty();

        Process log = new ProcessBuilder("git", "-C", dataDir.toString(), "log", "-1", "--format=%an|%s")
                .start();
        String output = new String(log.getInputStream().readAllBytes());
        log.waitFor();
        assertThat(output.trim()).isEqualTo("Тест Тестович|видалено тестові місяці");
    }

    private static void seed(DutyScheduleRepository repository, YearMonth month) {
        List<Engineer> engineers = List.of(new Engineer(1, "Тестовий І.", false));
        DutySchedule schedule = new DutySchedule(
                month, engineers,
                List.of(new DutyDay(1, DayOfWeek.TUESDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "сід " + month, "Тест Тестович", "test@example.com");
    }
}
