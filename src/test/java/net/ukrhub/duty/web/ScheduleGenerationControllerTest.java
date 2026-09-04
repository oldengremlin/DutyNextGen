/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
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
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Усі тести цього класу ділять один статичний {@code @TempDir} (і тому
 * один {@code RotationTemplateRepository}) — щоб шаблон, створений в
 * одному тесті, не зробив "кількість чергових" неоднозначною в іншому
 * (0/1/2+ шаблонів під K — {@code ScheduleGenerationController}), кожен
 * тест, що створює шаблони, використовує свою окрему кількість чергових
 * (K), не використану більше ніде в цьому файлі.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleGenerationControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DutyScheduleRepository repository;

    @Autowired
    private RotationTemplateRepository templateRepository;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
        registry.add("duty.templates-dir", () -> tempDir.resolve("templates").toString());
    }

    private RotationTemplate template(int id, String name, List<String> rows) {
        RotationTemplate template = new RotationTemplate(id, name, rows);
        templateRepository.save(template, "сід-шаблон " + id, "Тест", "test@example.com");
        return template;
    }

    /**
     * Позначки останніх двох днів, гарантовано розпізнавані генератором
     * (та сама якірна фаза, що й у {@code DutyScheduleGeneratorTest}) — на
     * два чергових, під класичний 2-слотовий шаблон. K=2 навмисно
     * ексклюзивна для цього тесту в цьому файлі.
     */
    private void seedGeneratableTwoRotating(YearMonth month) {
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

    /**
     * Простий сід на довільну кількість ротаційних адміністраторів —
     * без турботи про фазу (годиться лише там, де вона не перевіряється:
     * майстер вибору шаблону до пошуку фази не доходить, а генерація з
     * явного зсуву його не шукає).
     */
    private void seedSimpleRotating(YearMonth month, int rotatingCount) {
        List<Engineer> engineers = new ArrayList<>();
        for (int i = 1; i <= rotatingCount; i++) {
            engineers.add(new Engineer(i, "Черговий " + i, false));
        }
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        Map<Integer, DutyMark> lastDay0 = new LinkedHashMap<>();
        Map<Integer, DutyMark> lastDay1 = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            marks.put(e.number(), DutyMark.OFF);
            lastDay0.put(e.number(), DutyMark.OFF);
            lastDay1.put(e.number(), DutyMark.OFF);
        }
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false, marks));
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
        template(101, "Класика-101", List.of("DD--", "--DD")); // K=2, єдиний у файлі
        seedGeneratableTwoRotating(YearMonth.of(2034, 1));

        mockMvc.perform(post("/schedule/203401/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203402"));

        DutySchedule generated = repository.find(YearMonth.of(2034, 2)).orElseThrow();
        assertThat(generated.tid()).isEqualTo(101);
    }

    @Test
    @WithMockUser(username = "noc", roles = "EDITOR")
    void editorCannotGenerateNextMonth() throws Exception {
        seedGeneratableTwoRotating(YearMonth.of(2034, 3));

        mockMvc.perform(post("/schedule/203403/generate-next").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextRefusesWhenTargetAlreadyExists() throws Exception {
        seedGeneratableTwoRotating(YearMonth.of(2034, 4));
        seedSimple(YearMonth.of(2034, 5));

        mockMvc.perform(post("/schedule/203404/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203404"))
                .andExpect(flash().attributeExists("generationError"));
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextErrorsWhenNoTemplateMatchesRotatingCount() throws Exception {
        // K=6 — жодного шаблону під таку кількість у цьому файлі не створюється.
        seedSimpleRotating(YearMonth.of(2034, 6), 6);

        mockMvc.perform(post("/schedule/203406/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203406"))
                .andExpect(flash().attribute("generationError", org.hamcrest.Matchers.containsString("Немає жодного шаблону")));

        assertThat(repository.find(YearMonth.of(2034, 7))).isEmpty();
    }

    /**
     * Реальний випадок: під поточну кількість чергових підходить два
     * шаблони — кнопка «Згенерувати» не генерує мовчки, а веде на крок
     * вибору шаблону замість фази. K=3 — ексклюзивна для цього тесту.
     */
    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextRedirectsToTemplateChoiceWhenMultipleTemplatesMatch() throws Exception {
        template(201, "Перший 3-слотовий", List.of("D--", "-D-", "--D"));
        template(202, "Другий 3-слотовий", List.of("D-W", "WD-", "-WD"));
        seedSimpleRotating(YearMonth.of(2034, 8), 3);

        mockMvc.perform(post("/schedule/203408/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203408/generate-next/templates"));

        assertThat(repository.find(YearMonth.of(2034, 9))).isEmpty();

        mockMvc.perform(get("/schedule/203408/generate-next/templates"))
                .andExpect(status().isOk());
    }

    /**
     * Реальний production-баг: щойно додали чергового (K зросло), під нову
     * кількість підходить рівно один шаблон — але збережений хвіст
     * ({@code [ LastDayN ] }) ще старого розміру (записаний до зміни
     * ростеру), тож продовжити фазу неможливо. Раніше це показувало голу
     * помилку "кількість збережених останніх днів не збігається" замість
     * дружнього майстра; тепер, коли шаблон однозначний, а фазу продовжити
     * не вдалось, — одразу веде на крок вибору зсуву для цього ж шаблону.
     * K=5 — ексклюзивна для цього тесту.
     */
    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextFallsBackToOffsetWizardWhenSingleTemplateCannotContinuePhase() throws Exception {
        template(401, "П'ятислотовий", List.of("D----", "-D---", "--D--", "---D-", "----D"));

        List<Engineer> engineers = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            engineers.add(new Engineer(i, "Черговий " + i, false));
        }
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            marks.put(e.number(), DutyMark.OFF);
        }
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false, marks));
        // Хвіст лишився двослотовим — типова ситуація одразу після того, як
        // до ростеру додали чергових (K змінилось, а старий хвіст — ні).
        DutySchedule schedule = new DutySchedule(YearMonth.of(2034, 12), engineers, days,
                Map.of(1, DutyMark.OFF, 2, DutyMark.OFF), Map.of(1, DutyMark.OFF, 2, DutyMark.OFF));
        repository.save(schedule, "сід K-змінилось", "Тест", "test@example.com");

        mockMvc.perform(post("/schedule/203412/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203412/generate-next/offset?templateId=401"));

        assertThat(repository.find(YearMonth.of(2035, 1))).isEmpty();

        mockMvc.perform(get("/schedule/203412/generate-next/offset").param("templateId", "401"))
                .andExpect(status().isOk());
    }

    /** Крок 2 (вибір зсуву) і крок 3 (сама генерація без пошуку фази) — повний майстер. K=4 — ексклюзивна для цього тесту. */
    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void offsetWizardGeneratesWithoutPhaseSearch() throws Exception {
        RotationTemplate template = template(301, "Новий 4-слотовий", List.of("D---", "-D--", "--D-", "---D"));
        seedSimpleRotating(YearMonth.of(2034, 10), 4);

        mockMvc.perform(get("/schedule/203410/generate-next/offset").param("templateId", "301"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/schedule/203410/generate-next/offset").with(csrf())
                        .param("templateId", "301").param("offset", "0"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203411"));

        DutySchedule generated = repository.find(YearMonth.of(2034, 11)).orElseThrow();
        assertThat(generated.tid()).isEqualTo(template.id());
    }

    /** K=1 — ексклюзивна для цього тесту, жодного шаблону під неї немає. */
    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void generateNextReportsValidationFailureWithoutCreatingFile() throws Exception {
        DutySchedule schedule = new DutySchedule(
                YearMonth.of(2035, 6),
                List.of(new Engineer(1, "Єдиний черговий", false)),
                List.of(new DutyDay(1, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.DUTY),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(schedule, "сід", "Тест", "test@example.com");

        mockMvc.perform(post("/schedule/203506/generate-next").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/schedule/203506"))
                .andExpect(flash().attributeExists("generationError"));

        assertThat(repository.find(YearMonth.of(2035, 7))).isEmpty();
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
    void cannotDeleteCurrentMonth() throws Exception {
        YearMonth current = YearMonth.now();
        if (!repository.exists(current)) {
            seedSimple(current);
        }

        mockMvc.perform(post("/schedule/" + MonthPath.format(current) + "/delete").with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(repository.find(current)).isPresent();
    }

    @Test
    @WithMockUser(username = "noc", roles = "ADMIN")
    void cannotDeletePastMonth() throws Exception {
        YearMonth past = YearMonth.now().minusMonths(3);
        seedSimple(past);

        mockMvc.perform(post("/schedule/" + MonthPath.format(past) + "/delete").with(csrf()))
                .andExpect(status().isBadRequest());
        assertThat(repository.find(past)).isPresent();
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
