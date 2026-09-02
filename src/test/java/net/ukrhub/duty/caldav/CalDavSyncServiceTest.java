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
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null);
        return new DutyScheduleRepository(properties, new GitCommitService());
    }

    private static CalDavSyncService serviceFor(DutyScheduleRepository repository, Path tempDir, String baseUrl) {
        DutyProperties.Caldav caldav = new DutyProperties.Caldav(
                baseUrl, "noc", "secret", tempDir.resolve("caldav-state").toString());
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), caldav);
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
        service.syncCurrentAndNext(); // не мало б навіть спробувати з'єднання
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
                    tempDir.resolve("data").toString(), configDir.toString(), blankCaldav);
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

    @Test
    void pastDatedUidIsNeverDeletedEvenWhenNoLongerGenerated(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth month = YearMonth.of(2040, 9);
            seed(repository, month, DutyMark.DUTY);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            // Імітуємо стан, що лишився від дня, який уже минув: UID з датою
            // в далекому минулому, якого зараз generate() ніколи б не видав.
            CalDavSyncState.write(tempDir.resolve("caldav-state"), month,
                    Map.of("duty-20000101-9@duty.ukrhub.net", "stale-hash"));

            service.syncMonth(month);

            assertThat(server.deleteUids).isEmpty();
        }
    }

    @Test
    void syncCurrentAndNextCoversBothMonths(@TempDir Path tempDir) throws IOException {
        try (FakeCalDavServer server = new FakeCalDavServer("noc", "secret")) {
            DutyScheduleRepository repository = repositoryIn(tempDir);
            YearMonth current = YearMonth.now();
            // День 1 поточного місяця міг уже минути — сіємо на "сьогодні".
            seed(repository, current, java.time.LocalDate.now().getDayOfMonth(), DutyMark.DUTY);
            seed(repository, current.plusMonths(1), 1, DutyMark.WORK);
            CalDavSyncService service = serviceFor(repository, tempDir, server.baseUrl());

            service.syncCurrentAndNext();

            assertThat(server.putUids).hasSize(2);
        }
    }
}
