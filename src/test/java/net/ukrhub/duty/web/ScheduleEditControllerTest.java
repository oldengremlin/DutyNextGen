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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ScheduleEditControllerTest {

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

    // Кожен тест сіє свій власний, унікальний місяць — тестовий контекст Spring
    // (а разом з ним і git-репозиторій у tempDir) спільний для всіх методів
    // класу, і повторний запис ТОГО САМОГО місяця з тим самим вмістом дає
    // порожній diff ("nothing to commit"), а не помилку тесту.
    private void seedSchedule(YearMonth month) {
        DutySchedule schedule = new DutySchedule(
                month,
                List.of(new Engineer(1, "Стара І.", false)),
                List.of(new DutyDay(1, DayOfWeek.SATURDAY, Map.of(1, DutyMark.WORK))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "сід", "Тест", "test@example.com");
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editPageRequiresExistingSchedule() throws Exception {
        mockMvc.perform(get("/schedule/203201/edit"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "noc", roles = "VIEWER")
    void viewerCannotOpenEditPage() throws Exception {
        seedSchedule(YearMonth.of(2032, 11));

        mockMvc.perform(get("/schedule/203211/edit"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editPageRendersSelectsForExistingSchedule() throws Exception {
        seedSchedule(YearMonth.of(2032, 5));

        mockMvc.perform(get("/schedule/203205/edit"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Стара І.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("mark_1_1")));
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void adminCanChangeNameAndMarks() throws Exception {
        seedSchedule(YearMonth.of(2032, 6));

        mockMvc.perform(post("/schedule/203206/edit")
                        .with(csrf())
                        .param("name_1", "Нова І.")
                        .param("mark_1_1", "D"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203206"));

        DutySchedule updated = repository.find(YearMonth.of(2032, 6)).orElseThrow();
        assertThat(updated.engineer(1).name()).isEqualTo("Нова І.");
        assertThat(updated.days().get(0).markFor(1)).isEqualTo(DutyMark.DUTY);
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editorCanChangeMarksButNotNameOrType() throws Exception {
        seedSchedule(YearMonth.of(2032, 12));

        mockMvc.perform(post("/schedule/203212/edit")
                        .with(csrf())
                        .param("name_1", "Спроба перейменувати")
                        .param("onlyWorkdays_1", "true")
                        .param("mark_1_1", "D"))
                .andExpect(status().is3xxRedirection());

        DutySchedule updated = repository.find(YearMonth.of(2032, 12)).orElseThrow();
        assertThat(updated.engineer(1).name()).isEqualTo("Стара І.");
        assertThat(updated.engineer(1).onlyWorkdays()).isFalse();
        assertThat(updated.days().get(0).markFor(1)).isEqualTo(DutyMark.DUTY);
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void postingWithoutCsrfIsRejected() throws Exception {
        seedSchedule(YearMonth.of(2032, 7));

        mockMvc.perform(post("/schedule/203207/edit")
                        .param("mark_1_1", "D"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void addingEngineerAppendsToRosterAndAllDaysWithOff() throws Exception {
        seedSchedule(YearMonth.of(2032, 8));

        mockMvc.perform(post("/schedule/203208/edit/add-engineer")
                        .with(csrf())
                        .param("name", "Новак П.")
                        .param("onlyWorkdays", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203208/edit"));

        DutySchedule updated = repository.find(YearMonth.of(2032, 8)).orElseThrow();
        assertThat(updated.engineers()).hasSize(2);
        Engineer added = updated.engineer(2);
        assertThat(added.name()).isEqualTo("Новак П.");
        assertThat(added.onlyWorkdays()).isTrue();
        assertThat(updated.days().get(0).markFor(2)).isEqualTo(DutyMark.OFF);
        assertThat(updated.lastDay0()).containsKey(2);
        assertThat(updated.lastDay1()).containsKey(2);
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void removingEngineerDropsFromRosterAndAllDays() throws Exception {
        seedSchedule(YearMonth.of(2032, 9));

        mockMvc.perform(post("/schedule/203209/edit/remove-engineer")
                        .with(csrf())
                        .param("number", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203209/edit"));

        DutySchedule updated = repository.find(YearMonth.of(2032, 9)).orElseThrow();
        assertThat(updated.engineers()).isEmpty();
        assertThat(updated.days().get(0).marks()).doesNotContainKey(1);
        assertThat(updated.lastDay0()).doesNotContainKey(1);
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void removingUnknownEngineerIs404() throws Exception {
        seedSchedule(YearMonth.of(2032, 10));

        mockMvc.perform(post("/schedule/203210/edit/remove-engineer")
                        .with(csrf())
                        .param("number", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editorCannotAddOrRemoveEngineer() throws Exception {
        seedSchedule(YearMonth.of(2033, 1));

        mockMvc.perform(post("/schedule/203301/edit/add-engineer")
                        .with(csrf())
                        .param("name", "Хтось"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/schedule/203301/edit/remove-engineer")
                        .with(csrf())
                        .param("number", "1"))
                .andExpect(status().isForbidden());
    }
}
