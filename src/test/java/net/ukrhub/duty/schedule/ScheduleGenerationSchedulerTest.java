package net.ukrhub.duty.schedule;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.git.GitCommitService;
import net.ukrhub.duty.template.RotationTemplateRepository;
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
        DutyProperties properties = properties(tempDir);
        return new DutyScheduleRepository(properties, new GitCommitService());
    }

    private RotationTemplateRepository templateRepositoryIn(Path tempDir) {
        return new RotationTemplateRepository(properties(tempDir), new GitCommitService());
    }

    private static DutyProperties properties(Path tempDir) {
        return new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null,
                tempDir.resolve("templates").toString());
    }

    private static RotationTemplate classicTemplate(RotationTemplateRepository templateRepository) {
        RotationTemplate template = new RotationTemplate(1, "Класика", List.of("DD--", "--DD"));
        templateRepository.save(template, "сід-шаблон", "Тест", "test@example.com");
        return template;
    }

    @Test
    void generatesNextRealMonthWhenCurrentExistsAndNextIsMissing(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        RotationTemplateRepository templateRepository = templateRepositoryIn(tempDir);
        RotationTemplate template = classicTemplate(templateRepository);

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
        repository.save(new DutySchedule(currentReal, engineers, days, List.of(lastDay0, lastDay1), template.id()),
                "сід", "Тест", "test@example.com");

        new ScheduleGenerationScheduler(repository, templateRepository).generateNextRealMonthIfMissing();

        DutySchedule generated = repository.find(currentReal.plusMonths(1)).orElseThrow();
        assertThat(generated.tid()).isEqualTo(template.id());
    }

    @Test
    void doesNothingWhenCurrentRealMonthIsMissing(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        RotationTemplateRepository templateRepository = templateRepositoryIn(tempDir);

        new ScheduleGenerationScheduler(repository, templateRepository).generateNextRealMonthIfMissing();

        assertThat(repository.find(YearMonth.now().plusMonths(1))).isEmpty();
    }

    @Test
    void doesNothingWhenNextRealMonthAlreadyExists(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        RotationTemplateRepository templateRepository = templateRepositoryIn(tempDir);
        YearMonth next = YearMonth.now().plusMonths(1);
        DutySchedule existing = new DutySchedule(
                next,
                List.of(new Engineer(1, "Хтось", false)),
                List.of(new DutyDay(1, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(existing, "сід-наступний", "Тест", "test@example.com");

        new ScheduleGenerationScheduler(repository, templateRepository).generateNextRealMonthIfMissing();

        // Не перезаписано (той самий вміст, що й сіяли, — інженер "Хтось").
        assertThat(repository.find(next).orElseThrow().engineers()).extracting(Engineer::name)
                .containsExactly("Хтось");
    }

    /**
     * Реальний випадок (мандатна вимога користувача): фонова генерація
     * ніколи не вгадує заміну шаблону — якщо в поточному місяці нема
     * [ Tid ] (місяць старіший за появу шаблонів чи відредагований
     * вручну), просто пропускає, а не намагається підібрати якийсь
     * шаблон під поточну кількість чергових.
     */
    @Test
    void skipsWhenCurrentMonthHasNoTid(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        RotationTemplateRepository templateRepository = templateRepositoryIn(tempDir);
        classicTemplate(templateRepository);

        YearMonth currentReal = YearMonth.now();
        List<Engineer> engineers = List.of(new Engineer(2, "Черговий 1", false), new Engineer(3, "Черговий 2", false));
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false, Map.of(2, DutyMark.DUTY, 3, DutyMark.OFF)));
        // Легасі-конструктор (2 карти) — tid() лишається null.
        repository.save(new DutySchedule(currentReal, engineers, days,
                        Map.of(2, DutyMark.OFF, 3, DutyMark.DUTY), Map.of(2, DutyMark.DUTY, 3, DutyMark.OFF)),
                "сід-без-tid", "Тест", "test@example.com");

        new ScheduleGenerationScheduler(repository, templateRepository).generateNextRealMonthIfMissing();

        assertThat(repository.find(currentReal.plusMonths(1))).isEmpty();
    }

    /** Той самий принцип: кількість чергових розійшлася з шаблоном із [ Tid ] — пропускаємо, не вгадуємо заміну. */
    @Test
    void skipsWhenRotatingCountDiffersFromTemplateSlots(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        RotationTemplateRepository templateRepository = templateRepositoryIn(tempDir);
        RotationTemplate template = classicTemplate(templateRepository); // 2 слоти

        YearMonth currentReal = YearMonth.now();
        // Трьох чергових — уже не 2, а шаблон із [ Tid ] досі на 2.
        List<Engineer> engineers = List.of(
                new Engineer(2, "Черговий 1", false),
                new Engineer(3, "Черговий 2", false),
                new Engineer(4, "Черговий 3", false));
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false,
                Map.of(2, DutyMark.DUTY, 3, DutyMark.OFF, 4, DutyMark.OFF)));
        List<Map<Integer, DutyMark>> lastDays = List.of(
                Map.of(2, DutyMark.OFF, 3, DutyMark.DUTY, 4, DutyMark.OFF),
                Map.of(2, DutyMark.DUTY, 3, DutyMark.OFF, 4, DutyMark.OFF));
        repository.save(new DutySchedule(currentReal, engineers, days, lastDays, template.id()),
                "сід-K-розійшлось", "Тест", "test@example.com");

        new ScheduleGenerationScheduler(repository, templateRepository).generateNextRealMonthIfMissing();

        assertThat(repository.find(currentReal.plusMonths(1))).isEmpty();
    }
}
