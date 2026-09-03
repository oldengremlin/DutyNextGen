package net.ukrhub.duty.caldav;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.git.GitCommitService;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
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

class CalDavSyncServiceTest {

    private static DutyScheduleRepository repositoryIn(Path tempDir) {
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null,
                tempDir.resolve("templates").toString());
        return new DutyScheduleRepository(properties, new GitCommitService());
    }

    private static CalDavSyncService serviceFor(DutyScheduleRepository repository, Path tempDir, String baseUrl) {
        DutyProperties.Caldav caldav = new DutyProperties.Caldav(
                baseUrl, "noc", "secret", tempDir.resolve("caldav-state").toString());
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), caldav,
                tempDir.resolve("templates").toString());
        return new CalDavSyncService(repository, properties);
    }

    private static void seed(DutyScheduleRepository repository, YearMonth month, DutyMark markForEngineer1) {
        seed(repository, month, 1, markForEngineer1);
    }

    private static void seed(DutyScheduleRepository repository, YearMonth month, int day, DutyMark markForEngineer1) {
        List<Engineer> engineers = List.of(new Engineer(1, "Іванов І.", false));
        DutySchedule schedule = new DutySchedule(
                month, engineers,
                List.of(new DutyDay(day, DayOfWeek.TUESDAY, Map.of(1, markForEngineer1))),
                Map.of(), Map.of()
        );
        repository.save(schedule, "сід", "Тест", "test@example.com");
    }

    @Test
    void doesNothingWhenNotConfigured(@TempDir Path tempDir) {
        DutyScheduleRepository repository = repositoryIn(tempDir);
        seed(repository, YearMonth.now(), DutyMark.DUTY);
        CalDavSyncService service = serviceFor(repository, tempDir, "");

        assertThat(service.configured()).isFalse();
        service.syncRecentMonths(); // не мало б навіть спробувати з'єднання
    }

    /**
     * Реальний випадок: адміністратор поклав duty-caldav.conf (формат
     * застарілого duty-caldav-sync) у змонтований config-dir, не
     * торкаючись DUTY_CALDAV_*-змінних середовища (яких там і нема).
     */
    @Test
    void picksUpConfigFromDutyCaldavConfFileWhenEnvIsBlank(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            Path configDir = tempDir.resolve("config");
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve("duty-caldav.conf"), """
                    CALDAV_BASE_URL="%s"
                    CALDAV_USER="noc"
                    CALDAV_PASS="secret"
                    """.formatted(server.baseUrl()));

            DutyProperties.Caldav blankCaldav = new DutyProperties.Caldav(
                    "", "", "", tempDir.resolve("caldav-state").toString());
            DutyProperties properties = new DutyProperties(
                    tempDir.resolve("data").toString(), configDir.toString(), blankCaldav,
                    tempDir.resolve("templates").toString());
            DutyScheduleRepository repository = new DutyScheduleRepository(properties, new GitCommitService());
            YearMonth month = YearMonth.of(2040, 10);
            seed(repository, month, DutyMark.DUTY);

            CalDavSyncService service = new CalDavSyncService(repository, properties);

            assertThat(service.configured()).isTrue();
            service.syncMonth(month);

            assertThat(server.putUids).containsExactly("duty-20401001-1@duty.ukrhub.net");
        }
    }

    @Test
    void publishesEventsForFutureMonth(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth month = YearMonth.of(2040, 6);
            seed(repository, month, DutyMark.DUTY);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            service.syncMonth(month);

            assertThat(server.putUids).containsExactly("duty-20400601-1@duty.ukrhub.net");
            assertThat(server.storedBodies.get("duty-20400601-1@duty.ukrhub.net"))
                    .contains("SUMMARY:Чергування, Іванов І.\r\n");
        }
    }

    @Test
    void secondSyncWithUnchangedScheduleSkipsPut(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth month = YearMonth.of(2040, 7);
            seed(repository, month, DutyMark.DUTY);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            service.syncMonth(month);
            assertThat(server.putUids).hasSize(1);

            service.syncMonth(month);
            assertThat(server.putUids).as("другий синк без змін не повинен PUT'ити повторно").hasSize(1);
        }
    }

    @Test
    void changedMarkRepublishesAndClearedMarkDeletes(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth month = YearMonth.of(2040, 8);
            seed(repository, month, DutyMark.DUTY);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());
            service.syncMonth(month);
            assertThat(server.putUids).hasSize(1);

            // Позначку знято — подія має зникнути (DELETE), новий PUT не додається.
            seed(repository, month, DutyMark.OFF);
            service.syncMonth(month);

            assertThat(server.deleteUids).containsExactly("duty-20400801-1@duty.ukrhub.net");
            assertThat(server.putUids).hasSize(1);
        }
    }

    /**
     * Реальний випадок: позначку (напр. лікарняний) проставляють заднім
     * числом — попередній місяць синхронізується повністю (не лише
     * "від сьогодні й далі"), тож така правка так само дійде до CalDAV.
     */
    @Test
    void retroactiveEditInPreviousMonthIsPublished(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth previous = YearMonth.now().minusMonths(1);
            seed(repository, previous, 10, DutyMark.OFF);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            service.syncMonth(previous);
            assertThat(server.putUids).as("вихідний день ще не має події").isEmpty();

            // Лікарняний оформили постфактум.
            seed(repository, previous, 10, DutyMark.SICK);
            service.syncMonth(previous);

            assertThat(server.putUids).hasSize(1);
            assertThat(server.storedBodies.values()).anyMatch(body -> body.contains("SUMMARY:Лікарняний"));
        }
    }

    /** Так само навпаки: помилково проставлений заднім числом лікарняний можна прибрати — подія видаляється. */
    @Test
    void clearingRetroactiveMarkInPreviousMonthDeletesEvent(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth previous = YearMonth.now().minusMonths(1);
            seed(repository, previous, 12, DutyMark.SICK);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());
            service.syncMonth(previous);
            assertThat(server.putUids).hasSize(1);

            seed(repository, previous, 12, DutyMark.OFF);
            service.syncMonth(previous);

            assertThat(server.deleteUids).hasSize(1);
        }
    }

    /** Місяці, старіші за попередній, поза межами syncRecentMonths() — їх узагалі не читає й не чіпає. */
    @Test
    void monthsOlderThanPreviousAreNeverTouched(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth tooOld = YearMonth.now().minusMonths(3);
            seed(repository, tooOld, DutyMark.DUTY);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            service.syncRecentMonths();

            assertThat(server.putUids).isEmpty();
        }
    }

    @Test
    void syncRecentMonthsCoversPreviousCurrentAndNext(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth current = YearMonth.now();
            seed(repository, current.minusMonths(1), 1, DutyMark.SICK);
            seed(repository, current, 1, DutyMark.DUTY);
            seed(repository, current.plusMonths(1), 1, DutyMark.WORK);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            service.syncRecentMonths();

            assertThat(server.putUids).hasSize(3);
        }
    }

    /**
     * Реальний випадок: видалення адміністратора з графіка місяця
     * ({@code ScheduleEditController.removeEngineer}) прибирає його і зі
     * списку {@code engineers()}, і з marks кожного дня — тобто
     * {@code DutyIcsGenerator.generate()} для нього вже нічого не видає.
     * Окремого коду для цього не потрібно: те саме "зникло з newState"
     * DELETE, що й при знятті позначки, спрацьовує так само.
     */
    @Test
    void removedEngineerEventsAreDeletedFromCalDav(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth month = YearMonth.of(2040, 9);
            List<Engineer> engineers = List.of(
                    new Engineer(1, "Іванов І.", false),
                    new Engineer(2, "Петров П.", false));
            DutySchedule withBoth = new DutySchedule(
                    month, engineers,
                    List.of(new DutyDay(1, DayOfWeek.TUESDAY, Map.of(1, DutyMark.DUTY, 2, DutyMark.WORK))),
                    Map.of(), Map.of());
            repository.save(withBoth, "сід", "Тест", "test@example.com");
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());
            service.syncMonth(month);
            assertThat(server.putUids).hasSize(2);

            DutySchedule withoutSecond = new DutySchedule(
                    month, List.of(engineers.get(0)),
                    List.of(new DutyDay(1, DayOfWeek.TUESDAY, Map.of(1, DutyMark.DUTY))),
                    Map.of(), Map.of());
            repository.save(withoutSecond, "видалення адміністратора", "Тест", "test@example.com");
            service.syncMonth(month);

            assertThat(server.deleteUids).containsExactly("duty-20400901-2@duty.ukrhub.net");
            assertThat(server.putUids).as("другого адміністратора лише видалено, не перепубліковано").hasSize(2);
        }
    }
}
