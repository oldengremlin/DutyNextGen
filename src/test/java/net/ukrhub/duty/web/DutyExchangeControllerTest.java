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

import net.ukrhub.duty.auth.Role;
import net.ukrhub.duty.auth.UserStoreTestHelper;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.exchange.DutyExchangeRepository;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DutyExchangeControllerTest {

    @TempDir
    static Path tempDir;

    private static final YearMonth MONTH = YearMonth.from(LocalDate.now().plusMonths(1));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DutyScheduleRepository scheduleRepository;

    @Autowired
    private DutyExchangeRepository exchangeRepository;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
        registry.add("duty.templates-dir", () -> tempDir.resolve("templates").toString());
        registry.add("duty.exchanges-dir", () -> tempDir.resolve("exchanges").toString());
    }

    /**
     * Усі тести цього класу ділять один статичний {@code @TempDir} (і тому
     * один контекст Spring) — {@code @DynamicPropertySource} інакше не
     * прив'язати. Тому кожен тест, що створює пропозицію, бере свою окрему
     * пару днів (5↔9 для одного, 14↔18 для іншого), щоб не зіткнутись із
     * "дата вже задіяна" від пропозиції іншого тесту.
     */
    @BeforeEach
    void seedScheduleAndUsers() {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Кулинич А.", false),
                new Engineer(2, "Журавльова К.", false));
        List<DutyDay> days = List.of(
                new DutyDay(5, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF)),
                new DutyDay(9, DayOfWeek.MONDAY, Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY)),
                new DutyDay(14, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF)),
                new DutyDay(18, DayOfWeek.MONDAY, Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY)));
        DutySchedule schedule = new DutySchedule(MONTH, engineers, days, Map.of(), Map.of());
        scheduleRepository.save(schedule, "сід", "Тест", "test@example.com");

        Path usersFile = tempDir.resolve("config").resolve("users.txt");
        String hash = new BCryptPasswordEncoder().encode("secret123");
        UserStoreTestHelper.writeUser(usersFile, "kulinich", hash, Role.VIEWER, "Кулинич А.");
        UserStoreTestHelper.writeUser(usersFile, "zhuravlova", hash, Role.VIEWER, "Журавльова К.");
        UserStoreTestHelper.writeUser(usersFile, "viewer-no-link", hash, Role.VIEWER, null);
        UserStoreTestHelper.writeUser(usersFile, "admin1", hash, Role.ADMIN, null);
    }

    @Test
    void viewerWithoutLinkGetsForbidden() throws Exception {
        mockMvc.perform(get("/exchange").with(user("viewer-no-link").roles("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminSeesPageEvenWithoutLink() throws Exception {
        mockMvc.perform(get("/exchange").with(user("admin1").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("На затвердження")));
    }

    @Test
    void linkedEngineerSeesOwnDates() throws Exception {
        mockMvc.perform(get("/exchange").with(user("kulinich").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Кулинич А.")))
                .andExpect(content().string(containsString("Журавльова К.")));
    }

    /**
     * Реальний production-баг: П.І.Б. колеги (кирилиця, пробіл) прямо в
     * URL редиректу без кодування — Tomcat визнає такий {@code Location}
     * невалідним і просто відкидає заголовок (302 без Location, порожній
     * екран у браузері, дію при цьому вже виконано). MockMvc сам по собі
     * не проганяє відповідь через реальний сервлет-контейнер (тому цей
     * баг не впіймали інтеграційні тести до нього) — тут перевіряємо
     * інваріант напряму: значення {@code Location} має складатись лише з
     * printable ASCII, інакше жоден сервлет-контейнер його не прийме.
     */
    @Test
    void redirectAfterDraftAddIsAsciiSafe() throws Exception {
        MvcResult result = mockMvc.perform(post("/exchange/draft/add").with(user("kulinich").roles("VIEWER")).with(csrf())
                        .param("counterpart", "Журавльова К.")
                        .param("myDate", MONTH.atDay(5).toString())
                        .param("theirDate", MONTH.atDay(9).toString()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).isNotNull();
        assertThat(location).matches("^[\\x20-\\x7E]*$");
        assertThat(location).contains("counterpart=%D0%96");

        // прибрати за собою — тестовий клас ділить один @TempDir (і тому й
        // DutyExchangeDraftStore) на всі методи; інакше цей запис лишиться
        // в чернетці "kulinich" і задвоїть крок 5↔9 в fullFlowProposeAcceptApprove
        mockMvc.perform(post("/exchange/draft/remove").with(user("kulinich").roles("VIEWER")).with(csrf())
                .param("index", "0"));
    }

    @Test
    void fullFlowProposeAcceptApprove() throws Exception {
        mockMvc.perform(post("/exchange/draft/add").with(user("kulinich").roles("VIEWER")).with(csrf())
                        .param("counterpart", "Журавльова К.")
                        .param("myDate", MONTH.atDay(5).toString())
                        .param("theirDate", MONTH.atDay(9).toString()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/exchange/draft/submit").with(user("kulinich").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        DutyExchangeProposal proposal = proposalStartingAt(MONTH.atDay(5));
        assertThat(proposal.status()).isEqualTo(DutyExchangeStatus.PENDING);
        int id = proposal.id();

        mockMvc.perform(post("/exchange/{id}/accept", id).with(user("zhuravlova").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(exchangeRepository.find(id).orElseThrow().status()).isEqualTo(DutyExchangeStatus.ACCEPTED);

        mockMvc.perform(post("/exchange/{id}/approve", id).with(user("admin1").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(exchangeRepository.find(id).orElseThrow().status()).isEqualTo(DutyExchangeStatus.APPROVED);

        DutySchedule updated = scheduleRepository.find(MONTH).orElseThrow();
        DutyDay day5 = updated.days().stream().filter(d -> d.day() == 5).findFirst().orElseThrow();
        assertThat(day5.markFor(1)).isEqualTo(DutyMark.OFF);
        assertThat(day5.markFor(2)).isEqualTo(DutyMark.DUTY);
    }

    @Test
    void nonAdminCannotApprove() throws Exception {
        mockMvc.perform(post("/exchange/draft/add").with(user("kulinich").roles("VIEWER")).with(csrf())
                        .param("counterpart", "Журавльова К.")
                        .param("myDate", MONTH.atDay(14).toString())
                        .param("theirDate", MONTH.atDay(18).toString()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/exchange/draft/submit").with(user("kulinich").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        int id = proposalStartingAt(MONTH.atDay(14)).id();

        mockMvc.perform(post("/exchange/{id}/approve", id).with(user("kulinich").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private DutyExchangeProposal proposalStartingAt(LocalDate initiatorDate) {
        return exchangeRepository.findAll().stream()
                .filter(p -> p.steps().get(0).initiatorDate().equals(initiatorDate))
                .reduce((first, second) -> second) // останню за часом, якщо тест колись перезапустять у тому самому контексті
                .orElseThrow();
    }
}
