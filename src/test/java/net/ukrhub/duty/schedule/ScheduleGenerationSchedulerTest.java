package net.ukrhub.duty.schedule;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.git.GitCommitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleGenerationSchedulerTest {

    private DutyScheduleRepository repositoryIn(Path tempDir) {
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null);
        return new DutyScheduleRepository(properties, new GitCommitService());
    }

    @Test
    void generatesNextRealMonthWhenCurrentExistsAndNextIsMissing(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        YearMonth currentReal = YearMonth.now();

        List<Engineer> engineers = List.of(
                new Engineer(1, "Лише будні", true),
                new Engineer(2, "Черговий 1", false),
                new Engineer(3, "Черговий 2", false)
        );
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false,
                Map.of(1, DutyMark.WORK, 2, DutyMark.DUTY, 3, DutyMark.OFF)));
        Map<Integer, DutyMark> lastDay0 = Map.of(1, DutyMark.OFF, 2, DutyMark.OFF, 3, DutyMark.DUTY);
        Map<Integer, DutyMark> lastDay1 = Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY, 3, DutyMark.OFF);
        repository.save(new DutySchedule(currentReal, engineers, days, lastDay0, lastDay1),
                "сід", "Тест", "test@example.com");

        new ScheduleGenerationScheduler(repository).generateNextRealMonthIfMissing();

        assertThat(repository.find(currentReal.plusMonths(1))).isPresent();
    }

    @Test
    void doesNothingWhenCurrentRealMonthIsMissing(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);

        new ScheduleGenerationScheduler(repository).generateNextRealMonthIfMissing();

        assertThat(repository.find(YearMonth.now().plusMonths(1))).isEmpty();
    }

    @Test
    void doesNothingWhenNextRealMonthAlreadyExists(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        YearMonth next = YearMonth.now().plusMonths(1);
        DutySchedule existing = new DutySchedule(
                next,
                List.of(new Engineer(1, "Хтось", false)),
                List.of(new DutyDay(1, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(existing, "сід-наступний", "Тест", "test@example.com");

        new ScheduleGenerationScheduler(repository).generateNextRealMonthIfMissing();

        // Не перезаписано (той самий вміст, що й сіяли, — інженер "Хтось").
        assertThat(repository.find(next).orElseThrow().engineers()).extracting(Engineer::name)
                .containsExactly("Хтось");
    }
}
