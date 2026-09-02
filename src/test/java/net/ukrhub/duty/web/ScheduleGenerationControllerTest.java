package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ScheduleGenerationControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DutyScheduleRepository repository;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
    }

    /**
     * Позначки останніх двох днів, гарантовано розпізнавані генератором
     * (та сама якірна фаза, що й у {@code DutyScheduleGeneratorTest}).
     */
    private void seedGeneratable(YearMonth month) {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Лише будні", true),
                new Engineer(2, "Черговий 1", false),
                new Engineer(3, "Черговий 2", false)
        );
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false,
                Map.of(1, DutyMark.WORK, 2, DutyMark.DUTY, 3, DutyMark.OFF)));
        Map<Integer, DutyMark> lastDay0 = Map.of(1, DutyMark.OFF, 2, DutyMark.OFF, 3, DutyMark.DUTY);
        Map<Integer, DutyMark> lastDay1 = Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY, 3, DutyMark.OFF);
        DutySchedule schedule = new DutySchedule(month, engineers, days, lastDay0, lastDay1);
        repository.save(schedule, "сід " + month, "Тест", "test@example.com");
    }

    private void seedSimple(YearMonth month) {
        DutySchedule schedule = new DutySchedule(
                month,
                List.of(new Engineer(1, "Хтось", false)),
                List.of(new DutyDay(1, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "сід " + month, "Тест", "test@example.com");
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void adminCanGenerateNextMonth() throws Exception {
        seedGeneratable(YearMonth.of(2034, 1));

        mockMvc.perform(post("/schedule/203401/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203402"));

        assertThat(repository.find(YearMonth.of(2034, 2))).isPresent();
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editorCannotGenerateNextMonth() throws Exception {
        seedGeneratable(YearMonth.of(2034, 3));

        mockMvc.perform(post("/schedule/203403/generate-next").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextRefusesWhenTargetAlreadyExists() throws Exception {
        seedGeneratable(YearMonth.of(2034, 4));
        seedSimple(YearMonth.of(2034, 5));

        mockMvc.perform(post("/schedule/203404/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203404"))
                .andExpect(flash().attributeExists("generationError"));
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextReportsValidationFailureWithoutCreatingFile() throws Exception {
        DutySchedule schedule = new DutySchedule(
                YearMonth.of(2034, 6),
                List.of(new Engineer(1, "Єдиний черговий", false)),
                List.of(new DutyDay(1, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.DUTY),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "сід", "Тест", "test@example.com");

        mockMvc.perform(post("/schedule/203406/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203406"))
                .andExpect(flash().attributeExists("generationError"));

        assertThat(repository.find(YearMonth.of(2034, 7))).isEmpty();
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void adminCanDeleteFutureMonthCascadingToLaterOnes() throws Exception {
        YearMonth first = YearMonth.now().plusMonths(5);
        YearMonth second = first.plusMonths(1);
        YearMonth third = first.plusMonths(2);
        seedSimple(first);
        seedSimple(second);
        seedSimple(third);

        mockMvc.perform(post("/schedule/" + MonthPath.format(first) + "/delete").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(repository.find(first)).isEmpty();
        assertThat(repository.find(second)).isEmpty();
        assertThat(repository.find(third)).isEmpty();
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void cannotDeleteCurrentOrPastMonth() throws Exception {
        YearMonth current = YearMonth.now();
        if (!repository.exists(current)) {
            seedSimple(current);
        }

        mockMvc.perform(post("/schedule/" + MonthPath.format(current) + "/delete").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editorCannotDeleteMonth() throws Exception {
        YearMonth future = YearMonth.now().plusMonths(8);
        seedSimple(future);

        mockMvc.perform(post("/schedule/" + MonthPath.format(future) + "/delete").with(csrf()))
                .andExpect(status().isForbidden());
    }
}
