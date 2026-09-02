package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DutyScheduleRepository repository;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
    }

    private TestRestTemplate authed() {
        Path usersFile = tempDir.resolve("config").resolve("users.txt");
        if (!usersFile.toFile().exists()) {
            String hash = new BCryptPasswordEncoder().encode("secret123");
            net.ukrhub.duty.auth.UserStoreTestHelper.writeUser(usersFile, "noc", hash);
        }
        return restTemplate.withBasicAuth("noc", "secret123");
    }

    @Test
    void rootRedirectsToCurrentMonth() {
        ResponseEntity<String> response = authed().getForEntity("/", String.class);
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.FOUND);
    }

    @Test
    void unknownMonthShowsFriendlyEmptyState() {
        ResponseEntity<String> response = authed().getForEntity("/schedule/210001", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Даних за");
    }

    @Test
    void badlyFormattedMonthIs404() {
        ResponseEntity<String> response = authed().getForEntity("/schedule/not-a-month", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void existingMonthRendersEngineerNamesAndMarks() {
        DutySchedule schedule = new DutySchedule(
                YearMonth.of(2031, 3),
                List.of(new Engineer(1, "Марченко І.", false)),
                List.of(new DutyDay(1, DayOfWeek.SATURDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "тест", "Тест", "test@example.com");

        ResponseEntity<String> response = authed().getForEntity("/schedule/203103", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Марченко І.");
        assertThat(response.getBody()).contains("mark-duty");
    }

    /**
     * Регресія на реальний production-баг: сторінка падала з 500 на
     * місяці зі святковим маркером ("1  Fr*" у файлі) — рендер шаблону
     * викликав UkrainianCalendar.dayOfWeekShort(day.dow) при dow == null.
     */
    @Test
    void monthWithHolidayRendersWithoutError() {
        DutySchedule schedule = new DutySchedule(
                YearMonth.of(2031, 4),
                List.of(new Engineer(1, "Марченко І.", false)),
                List.of(new DutyDay(1, DayOfWeek.FRIDAY, true, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "тест-свято", "Тест", "test@example.com");

        ResponseEntity<String> response = authed().getForEntity("/schedule/203104", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("holiday");
    }
}
