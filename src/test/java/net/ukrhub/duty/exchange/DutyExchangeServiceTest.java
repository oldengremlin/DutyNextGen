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
package net.ukrhub.duty.exchange;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.domain.DutyExchangeStep;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.git.GitCommitService;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DutyExchangeServiceTest {

    /** Наступний місяць від "сьогодні" тестового прогону — застраховано від "дата вже в минулому" незалежно від того, коли ці тести реально запускаються. */
    private static final YearMonth MONTH = YearMonth.from(LocalDate.now().plusMonths(1));
    private static final Engineer KULINICH = new Engineer(1, "Кулинич А.", false);
    private static final Engineer ZHURAVLOVA = new Engineer(2, "Журавльова К.", false);
    private static final Engineer ONLY_WORKDAYS = new Engineer(3, "Леонов О.", true);

    private DutyScheduleRepository scheduleRepository;
    private DutyExchangeRepository exchangeRepository;
    private DutyExchangeService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null,
                tempDir.resolve("templates").toString(), tempDir.resolve("exchanges").toString());
        GitCommitService git = new GitCommitService();
        scheduleRepository = new DutyScheduleRepository(properties, git);
        exchangeRepository = new DutyExchangeRepository(properties, git);
        service = new DutyExchangeService(exchangeRepository, scheduleRepository, git);

        // day 5: Кулинич D, Журавльова OFF, Леонов W
        // day 9: Кулинич OFF, Журавльова D, Леонов W
        // day 14: Кулинич W, Журавльова OFF, Леонов W
        // day 21: Кулинич OFF, Журавльова W, Леонов W
        // day 25: Кулинич D, Журавльова VACATION (О), Леонов W — навмисно небезпечна "хрестова" клітинка
        List<Engineer> engineers = List.of(KULINICH, ZHURAVLOVA, ONLY_WORKDAYS);
        List<DutyDay> days = List.of(
                new DutyDay(5, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF, 3, DutyMark.WORK)),
                new DutyDay(9, DayOfWeek.MONDAY, Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY, 3, DutyMark.WORK)),
                new DutyDay(14, DayOfWeek.MONDAY, Map.of(1, DutyMark.WORK, 2, DutyMark.OFF, 3, DutyMark.WORK)),
                new DutyDay(21, DayOfWeek.MONDAY, Map.of(1, DutyMark.OFF, 2, DutyMark.WORK, 3, DutyMark.WORK)),
                new DutyDay(25, DayOfWeek.MONDAY, Map.of(1, DutyMark.DUTY, 2, DutyMark.VACATION, 3, DutyMark.WORK))
        );
        DutySchedule schedule = new DutySchedule(MONTH, engineers, days, Map.of(), Map.of());
        scheduleRepository.save(schedule, "сід", "Тест", "test@example.com");
    }

    private DutyExchangeStep dutyStep(int fromDay, int toDay) {
        return new DutyExchangeStep(DutyMark.DUTY, MONTH.atDay(fromDay), MONTH.atDay(toDay));
    }

    // --- datesFor ---

    @Test
    void datesForListsOnlyFutureDutyAndWorkMarks() {
        List<DutySwappableDate> dates = service.datesFor("Кулинич А.");

        assertThat(dates).extracting(DutySwappableDate::date)
                .containsExactlyInAnyOrder(MONTH.atDay(5), MONTH.atDay(14), MONTH.atDay(25));
    }

    @Test
    void datesForExcludesOnlyWorkdaysEngineer() {
        assertThat(service.datesFor("Леонов О.")).isEmpty();
    }

    @Test
    void datesForMarksLockedDatesFromActiveProposal() {
        service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        List<DutySwappableDate> dates = service.datesFor("Кулинич А.");

        assertThat(dates.stream().filter(d -> d.date().equals(MONTH.atDay(5))).findFirst().orElseThrow().locked()).isTrue();
        assertThat(dates.stream().filter(d -> d.date().equals(MONTH.atDay(14))).findFirst().orElseThrow().locked()).isFalse();
    }

    // --- propose: валідації ---

    @Test
    void proposeCreatesPendingProposal() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        assertThat(proposal.status()).isEqualTo(DutyExchangeStatus.PENDING);
        assertThat(exchangeRepository.find(proposal.id())).contains(proposal);
    }

    @Test
    void proposeRejectsSelfExchange() {
        assertThatThrownBy(() -> service.propose("Кулинич А.", "kulinich", "Кулинич А.", List.of(dutyStep(5, 9))))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    @Test
    void proposeRejectsWhenGivenDateIsNotExpectedType() {
        // 14-те в Кулинича — W, не D
        DutyExchangeStep mismatched = new DutyExchangeStep(DutyMark.DUTY, MONTH.atDay(14), MONTH.atDay(9));

        assertThatThrownBy(() -> service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(mismatched)))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    @Test
    void proposeRejectsWhenCrossCellIsVacation() {
        // 25-те в Кулинича D, але 25-те в Журавльової — VACATION (О), не можна підміняти
        assertThatThrownBy(() -> service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(25, 9))))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    @Test
    void proposeRejectsDateAlreadyLockedByAnotherActiveProposal() {
        service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        assertThatThrownBy(() -> service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9))))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    @Test
    void proposeRejectsPastDate() {
        DutyExchangeStep past = new DutyExchangeStep(DutyMark.DUTY, LocalDate.now().minusDays(1), MONTH.atDay(9));

        assertThatThrownBy(() -> service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(past)))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    // --- accept / decline ---

    @Test
    void acceptRequiresBeingTheCounterpart() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        assertThatThrownBy(() -> service.accept(proposal.id(), "kulinich", "Кулинич А."))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    @Test
    void acceptTransitionsToAccepted() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        DutyExchangeProposal accepted = service.accept(proposal.id(), "zhuravlova", "Журавльова К.");

        assertThat(accepted.status()).isEqualTo(DutyExchangeStatus.ACCEPTED);
    }

    @Test
    void declineTransitionsToDeclined() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        DutyExchangeProposal declined = service.decline(proposal.id(), "zhuravlova", "Журавльова К.");

        assertThat(declined.status()).isEqualTo(DutyExchangeStatus.DECLINED);
    }

    // --- approve: застосування до графіка ---

    @Test
    void approveSwapsMarksOnBothDatesAndKeepsSingleCommit() throws Exception {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));
        service.accept(proposal.id(), "zhuravlova", "Журавльова К.");
        int commitsBefore = commitCount(scheduleRepository.dataDir());

        DutyExchangeProposal approved = service.approve(proposal.id(), "admin");

        assertThat(approved.status()).isEqualTo(DutyExchangeStatus.APPROVED);
        DutySchedule updated = scheduleRepository.find(MONTH).orElseThrow();
        DutyDay day5 = updated.days().stream().filter(d -> d.day() == 5).findFirst().orElseThrow();
        DutyDay day9 = updated.days().stream().filter(d -> d.day() == 9).findFirst().orElseThrow();
        assertThat(day5.markFor(1)).isEqualTo(DutyMark.OFF);   // Кулинич віддав 5-те
        assertThat(day5.markFor(2)).isEqualTo(DutyMark.DUTY);  // ...Журавльовій
        assertThat(day9.markFor(2)).isEqualTo(DutyMark.OFF);   // Журавльова віддала 9-те
        assertThat(day9.markFor(1)).isEqualTo(DutyMark.DUTY);  // ...Кулиничу

        assertThat(commitCount(scheduleRepository.dataDir())).isEqualTo(commitsBefore + 1);
    }

    @Test
    void approveCommitsAsInitiatorNotAdmin() throws Exception {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));
        service.accept(proposal.id(), "zhuravlova", "Журавльова К.");

        service.approve(proposal.id(), "admin");

        Process log = new ProcessBuilder("git", "-C", scheduleRepository.dataDir().toString(),
                "log", "-1", "--format=%an").start();
        String author = new String(log.getInputStream().readAllBytes()).strip();
        log.waitFor();
        assertThat(author).isEqualTo("kulinich");
    }

    @Test
    void approveRequiresAcceptedStatus() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        assertThatThrownBy(() -> service.approve(proposal.id(), "admin"))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    @Test
    void rejectTransitionsToRejectedWithoutTouchingSchedule() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));
        service.accept(proposal.id(), "zhuravlova", "Журавльова К.");

        DutyExchangeProposal rejected = service.reject(proposal.id(), "admin");

        assertThat(rejected.status()).isEqualTo(DutyExchangeStatus.REJECTED);
        DutySchedule unchanged = scheduleRepository.find(MONTH).orElseThrow();
        DutyDay day5 = unchanged.days().stream().filter(d -> d.day() == 5).findFirst().orElseThrow();
        assertThat(day5.markFor(1)).isEqualTo(DutyMark.DUTY);
    }

    // --- анулювання, коли графік змінився ---

    @Test
    void acceptAutoCancelsWhenReferencedMonthDisappeared(@TempDir Path ignored) throws Exception {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));
        Files.delete(scheduleRepository.fileFor(MONTH));

        DutyExchangeProposal result = service.accept(proposal.id(), "zhuravlova", "Журавльова К.");

        assertThat(result.status()).isEqualTo(DutyExchangeStatus.STALE_CANCELLED);
    }

    @Test
    void revalidateAllCancelsProposalsForDeletedMonths() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));
        scheduleRepository.delete(List.of(MONTH), "видалено для тесту", "admin", "admin@duty.local");

        service.revalidateAll();

        assertThat(exchangeRepository.find(proposal.id()).orElseThrow().status())
                .isEqualTo(DutyExchangeStatus.STALE_CANCELLED);
    }

    // --- acknowledge ---

    @Test
    void acknowledgeDeletesTerminalProposal() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));
        service.decline(proposal.id(), "zhuravlova", "Журавльова К.");

        service.acknowledge(proposal.id(), "kulinich", "Кулинич А.");

        assertThat(exchangeRepository.find(proposal.id())).isEmpty();
    }

    @Test
    void acknowledgeRejectsWhileStillActive() {
        DutyExchangeProposal proposal = service.propose("Кулинич А.", "kulinich", "Журавльова К.", List.of(dutyStep(5, 9)));

        assertThatThrownBy(() -> service.acknowledge(proposal.id(), "kulinich", "Кулинич А."))
                .isInstanceOf(DutyExchangeValidationException.class);
    }

    private static int commitCount(Path dataDir) throws Exception {
        Process log = new ProcessBuilder("git", "-C", dataDir.toString(), "log", "--oneline").start();
        String output = new String(log.getInputStream().readAllBytes());
        log.waitFor();
        return (int) output.strip().lines().count();
    }
}
