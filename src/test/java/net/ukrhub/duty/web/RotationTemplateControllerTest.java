package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.template.RotationTemplateRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RotationTemplateControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RotationTemplateRepository repository;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
        registry.add("duty.templates-dir", () -> tempDir.resolve("templates").toString());
    }

    @Test
    @WithMockUser(roles = "EDITOR")
    void nonAdminCannotOpenTemplatesList() throws Exception {
        mockMvc.perform(get("/admin/templates"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void creatingTemplateAsksSlotsThenOpensBlankEditor() throws Exception {
        String redirect = mockMvc.perform(post("/admin/templates").with(csrf()).param("slots", "3"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        int id = idFromEditRedirect(redirect);
        RotationTemplate created = repository.find(id).orElseThrow();
        assertThat(created.slots()).isEqualTo(3);
        assertThat(created.period()).isEqualTo(1);
        assertThat(created.rows()).allMatch(row -> row.equals("-"));

        mockMvc.perform(get("/admin/templates"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3 чергових")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void slotsOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/admin/templates").with(csrf()).param("slots", "1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/templates").with(csrf()).param("slots", "50"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addAndRemoveDayChangePeriodWithoutTouchingSlots() throws Exception {
        int id = createTemplate(2);

        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(repository.find(id).orElseThrow().period()).isEqualTo(3);

        mockMvc.perform(post("/admin/templates/{id}/remove-day", id).with(csrf()))
                .andExpect(status().is3xxRedirection());
        RotationTemplate afterRemove = repository.find(id).orElseThrow();
        assertThat(afterRemove.period()).isEqualTo(2);
        assertThat(afterRemove.slots()).isEqualTo(2);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void removingLastDayIsRejected() throws Exception {
        int id = createTemplate(2);

        mockMvc.perform(post("/admin/templates/{id}/remove-day", id).with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(repository.find(id).orElseThrow().period()).isEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editingSavesNameAndMarks() throws Exception {
        int id = createTemplate(2);
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()));
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()));
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()));
        // період тепер 4

        mockMvc.perform(post("/admin/templates/{id}/edit", id).with(csrf())
                        .param("name", "Чотири дні на двох")
                        .param("mark_1_1", "D").param("mark_1_2", "D").param("mark_1_3", "-").param("mark_1_4", "-")
                        .param("mark_2_1", "-").param("mark_2_2", "-").param("mark_2_3", "D").param("mark_2_4", "D"))
                .andExpect(status().is3xxRedirection());

        RotationTemplate saved = repository.find(id).orElseThrow();
        assertThat(saved.name()).isEqualTo("Чотири дні на двох");
        assertThat(saved.rows()).containsExactly("DD--", "--DD");
    }

    /**
     * Реальний випадок, знайдений живою перевіркою в браузері: усі
     * {@code <select>} на сторінці редагування показували "D" незалежно
     * від збереженого значення. Причина була в
     * {@code markAt(...).code() == 'D'} — одинарні лапки в SpEL
     * позначають String, а {@code code()} повертає {@code char}:
     * порівняння типів мовчки завжди хибне, жоден {@code <option>} не
     * ставав {@code selected}, і браузер підставляв перший за
     * замовчуванням. {@link RotationTemplate#markCodeAt} — фікс; цей
     * тест перевіряє саме відрендерений HTML (а не лише збережений
     * файл), щоб такий регрес не міг знову пройти непомітно.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void editPageMarksCorrectOptionAsSelectedForEveryCell() throws Exception {
        int id = createTemplate(2);
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()));
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()));
        mockMvc.perform(post("/admin/templates/{id}/add-day", id).with(csrf()));
        mockMvc.perform(post("/admin/templates/{id}/edit", id).with(csrf())
                        .param("name", "Тест")
                        .param("mark_1_1", "D").param("mark_1_2", "D").param("mark_1_3", "-").param("mark_1_4", "-")
                        .param("mark_2_1", "-").param("mark_2_2", "-").param("mark_2_3", "D").param("mark_2_4", "D"));

        String html = mockMvc.perform(get("/admin/templates/{id}/edit", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(selectedValue(html, "mark_1_1")).isEqualTo("D");
        assertThat(selectedValue(html, "mark_1_2")).isEqualTo("D");
        assertThat(selectedValue(html, "mark_1_3")).as("слот 1, день 3 — фактично '-'").isEqualTo("-");
        assertThat(selectedValue(html, "mark_1_4")).isEqualTo("-");
        assertThat(selectedValue(html, "mark_2_1")).isEqualTo("-");
        assertThat(selectedValue(html, "mark_2_2")).isEqualTo("-");
        assertThat(selectedValue(html, "mark_2_3")).as("слот 2, день 3 — фактично 'D'").isEqualTo("D");
        assertThat(selectedValue(html, "mark_2_4")).isEqualTo("D");
    }

    /** Значення {@code value} того {@code <option>}, що позначений {@code selected}, у {@code <select name="selectName">}. */
    private static String selectedValue(String html, String selectName) {
        java.util.regex.Matcher select = java.util.regex.Pattern
                .compile("name=\"" + selectName + "\">(.*?)</select>", java.util.regex.Pattern.DOTALL)
                .matcher(html);
        assertThat(select.find()).as("немає <select name=\"" + selectName + "\">").isTrue();
        java.util.regex.Matcher option = java.util.regex.Pattern
                .compile("value=\"([^\"]*)\" selected=\"selected\"")
                .matcher(select.group(1));
        assertThat(option.find()).as("жоден option у <select name=\"" + selectName + "\"> не selected").isTrue();
        return option.group(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownMarkCodeSilentlyBecomesOff() throws Exception {
        int id = createTemplate(2);

        mockMvc.perform(post("/admin/templates/{id}/edit", id).with(csrf())
                        .param("name", "Тест")
                        .param("mark_1_1", "O")
                        .param("mark_2_1", "D"))
                .andExpect(status().is3xxRedirection());

        RotationTemplate saved = repository.find(id).orElseThrow();
        assertThat(saved.rows()).containsExactly("-", "D");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteTemplate() throws Exception {
        int id = createTemplate(2);

        mockMvc.perform(post("/admin/templates/{id}/delete", id).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(repository.find(id)).isEmpty();
        mockMvc.perform(get("/admin/templates/{id}/edit", id))
                .andExpect(status().isNotFound());
    }

    private int createTemplate(int slots) throws Exception {
        String redirect = mockMvc.perform(post("/admin/templates").with(csrf()).param("slots", String.valueOf(slots)))
                .andReturn().getResponse().getRedirectedUrl();
        return idFromEditRedirect(redirect);
    }

    private static int idFromEditRedirect(String redirect) {
        // "/admin/templates/{id}/edit"
        String[] parts = redirect.split("/");
        return Integer.parseInt(parts[parts.length - 2]);
    }
}
