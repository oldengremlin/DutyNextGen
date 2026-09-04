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

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.domain.DutyExchangeStep;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.git.GitCommitService;
import net.ukrhub.duty.schedule.DutyScheduleFormat;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Бізнес-логіка обміну чергуваннями: список дат, доступних для обміну,
 * створення/прийняття/відхилення пропозиції, застосування схваленої
 * пропозиції до графіка, автоматичне анулювання, якщо графік з тих пір
 * змінився.
 *
 * <p>Механіка обміну — {@link #applyToSchedule}: на кожній із двох дат
 * кроку ({@code DutyExchangeStep}) позначки ініціатора й колеги просто
 * міняються місцями цілком (те, що "хрестом" дістається іншій людині —
 * найчастіше {@code OFF}, рідше {@code WORK} — саме тому обмін дає
 * "кілька вихідних поспіль без втрати робочих годин": сумарна кількість
 * D/W не змінюється, лише перерозподіляється по датах).
 */
@Service
public class DutyExchangeService {

    private static final String SYSTEM_USERNAME = "duty-nextgen";

    private final DutyExchangeRepository exchangeRepository;
    private final DutyScheduleRepository scheduleRepository;
    private final GitCommitService gitCommitService;

    /**
     * {@link GitCommitService} — напряму, а не через репозиторій графіка:
     * затвердження пропозиції може зачепити два різні місячні файли, і вони
     * мають лягти ОДНИМ комітом, інакше обмін лишиться застосованим наполовину.
     */
    public DutyExchangeService(DutyExchangeRepository exchangeRepository, DutyScheduleRepository scheduleRepository,
                                GitCommitService gitCommitService) {
        this.exchangeRepository = exchangeRepository;
        this.scheduleRepository = scheduleRepository;
        this.gitCommitService = gitCommitService;
    }

    /**
     * Усі наявні місяці від поточного й далі, прочитані рівно по одному
     * разу. Кожен виклик {@code scheduleRepository.find} — це читання й
     * повний розбір текстового файлу; сторінка обміну питає розклад
     * кілька разів поспіль (свої дати, дати колеги, перевірка кроків), і
     * без спільного знімка той самий файл розбирався б знову й знову.
     */
    private Map<YearMonth, DutySchedule> upcomingSchedules() {
        Map<YearMonth, DutySchedule> schedules = new LinkedHashMap<>();
        for (YearMonth month : scheduleRepository.existingMonthsFrom(YearMonth.now())) {
            scheduleRepository.find(month).ifPresent(schedule -> schedules.put(month, schedule));
        }
        return schedules;
    }

    /** Чергові інженери (не "лише робочі дні"), які фігурують хоч в одному з наявних майбутніх місяців. */
    public List<String> rotationEngineerNames() {
        Set<String> names = new LinkedHashSet<>();
        for (DutySchedule schedule : upcomingSchedules().values()) {
            schedule.engineers().stream()
                    .filter(e -> !e.onlyWorkdays())
                    .forEach(e -> names.add(e.name()));
        }
        return List.copyOf(names);
    }

    /** Дати {@code engineerName} з позначкою D чи W, строго в майбутньому, по всіх наявних місяцях. */
    public List<DutySwappableDate> datesFor(String engineerName) {
        Set<LocalDate> locked = lockedDates(engineerName, null, exchangeRepository.findAll());
        LocalDate today = LocalDate.now();
        List<DutySwappableDate> result = new ArrayList<>();
        for (var entry : upcomingSchedules().entrySet()) {
            DutySchedule schedule = entry.getValue();
            Optional<Engineer> engineer = rotationEngineer(schedule, engineerName);
            if (engineer.isEmpty()) {
                continue;
            }
            for (DutyDay day : schedule.days()) {
                LocalDate date = entry.getKey().atDay(day.day());
                if (!date.isAfter(today)) {
                    continue;
                }
                DutyMark mark = day.markFor(engineer.get().number());
                if (mark == DutyMark.DUTY || mark == DutyMark.WORK) {
                    result.add(new DutySwappableDate(date, mark, locked.contains(date)));
                }
            }
        }
        return result;
    }

    /**
     * Усі пропозиції за участю цього інженера — байдуже, як ініціатора чи як
     * колеги, і в будь-якому стані (журнал показує й завершені, доки їх не
     * «визнали» кнопкою «Зрозуміло»).
     */
    public List<DutyExchangeProposal> proposalsFor(String engineerName) {
        return exchangeRepository.findAll().stream()
                .filter(p -> p.initiatorName().equals(engineerName) || p.counterpartName().equals(engineerName))
                .toList();
    }

    /** Прийняті колегою, ще не вирішені адміністратором. */
    public List<DutyExchangeProposal> pendingAdminApproval() {
        return exchangeRepository.findAll().stream()
                .filter(p -> p.status() == DutyExchangeStatus.ACCEPTED)
                .toList();
    }

    /**
     * Скільки пропозицій обміну потребує уваги цього користувача — для
     * бейджа біля посилання на {@code /exchange}
     * ({@code DutyExchangeNoticeAdvice}). Рахує за ОДИН прохід по сховищу:
     * бейдж малюється на кожній сторінці застосунку, а роздільні
     * {@code pendingAdminApproval()} + {@code proposalsFor()} означали два
     * повних обходи каталогу з розбором кожного файлу на КОЖЕН запит,
     * включно з переглядом графіка, який до обміну взагалі не має
     * стосунку.
     *
     * @param engineerName П.І.Б. інженера, до якого прив'язаний користувач,
     *                     або {@code null}, якщо прив'язки нема
     * @param isAdmin      чи додавати сюди ще й чергу на затвердження
     */
    public int attentionCountFor(String engineerName, boolean isAdmin) {
        int count = 0;
        for (DutyExchangeProposal proposal : exchangeRepository.findAll()) {
            if (isAdmin && proposal.status() == DutyExchangeStatus.ACCEPTED) {
                count++;
                continue;
            }
            if (engineerName == null || !isParty(proposal, engineerName)) {
                continue;
            }
            boolean awaitingMyDecision = proposal.status() == DutyExchangeStatus.PENDING
                    && proposal.counterpartName().equals(engineerName);
            boolean unreadOutcome = !proposal.status().active();
            if (awaitingMyDecision || unreadOutcome) {
                count++;
            }
        }
        return count;
    }

    /** Чи бере {@code engineerName} участь у пропозиції — байдуже, як ініціатор чи як колега. */
    private static boolean isParty(DutyExchangeProposal proposal, String engineerName) {
        return proposal.initiatorName().equals(engineerName) || proposal.counterpartName().equals(engineerName);
    }

    /**
     * Створює пропозицію в стані {@code PENDING}.
     *
     * @param initiatorUsername обліковий запис — окремо від П.І.Б., для авторства
     *        git-коміту при подальшому застосуванні
     * @throws DutyExchangeValidationException якщо хоч один крок порушує правила обміну
     */
    public DutyExchangeProposal propose(String initiatorName, String initiatorUsername,
                                         String counterpartName, List<DutyExchangeStep> steps) {
        if (steps.isEmpty()) {
            throw new DutyExchangeValidationException("Пропозиція обміну без жодного кроку");
        }
        validateProposal(initiatorName, counterpartName, steps, null);

        int id = exchangeRepository.nextId();
        DutyExchangeProposal proposal = new DutyExchangeProposal(id, initiatorName, initiatorUsername, counterpartName,
                steps, DutyExchangeStatus.PENDING, LocalDateTime.now());
        exchangeRepository.save(proposal, "Пропозиція обміну: " + initiatorName + " ↔ " + counterpartName,
                initiatorUsername, initiatorUsername + "@duty.local");
        return proposal;
    }

    /**
     * Колега погоджується: {@code PENDING} → {@code ACCEPTED}. Перед цим
     * пропозиція перевіряється наново ({@link #checkpoint}) — графік міг
     * змінитися, доки вона чекала.
     */
    public DutyExchangeProposal accept(int id, String actingUsername, String actingEngineerName) {
        DutyExchangeProposal proposal = requireProposal(id);
        requireStatus(proposal, DutyExchangeStatus.PENDING);
        requireParty(proposal.counterpartName(), actingEngineerName);

        proposal = checkpoint(proposal);
        if (proposal.status() != DutyExchangeStatus.PENDING) {
            return proposal;
        }
        DutyExchangeProposal accepted = proposal.withStatus(DutyExchangeStatus.ACCEPTED);
        exchangeRepository.save(accepted, "Прийнято колегою: " + proposal.counterpartName(),
                actingUsername, actingUsername + "@duty.local");
        return accepted;
    }

    /**
     * Колега відмовляє: {@code PENDING} → {@code DECLINED}. Перевіряти графік
     * тут нема сенсу — відмова однаково нічого в ньому не міняє.
     */
    public DutyExchangeProposal decline(int id, String actingUsername, String actingEngineerName) {
        DutyExchangeProposal proposal = requireProposal(id);
        requireStatus(proposal, DutyExchangeStatus.PENDING);
        requireParty(proposal.counterpartName(), actingEngineerName);

        DutyExchangeProposal declined = proposal.withStatus(DutyExchangeStatus.DECLINED);
        exchangeRepository.save(declined, "Відхилено колегою: " + proposal.counterpartName(),
                actingUsername, actingUsername + "@duty.local");
        return declined;
    }

    /**
     * Адміністратор затверджує: {@code ACCEPTED} → {@code APPROVED} із
     * застосуванням до графіка. Право на дію дає {@code SecurityConfig}
     * (лише {@code ADMIN}), тому сторона тут не перевіряється.
     *
     * @throws DutyExchangeValidationException якщо пропозиція не в тому стані
     *         або графік уже не дозволяє обмін
     */
    public DutyExchangeProposal approve(int id, String adminUsername) {
        DutyExchangeProposal proposal = requireProposal(id);
        requireStatus(proposal, DutyExchangeStatus.ACCEPTED);

        proposal = checkpoint(proposal);
        if (proposal.status() != DutyExchangeStatus.ACCEPTED) {
            return proposal;
        }
        applyToSchedule(proposal);
        DutyExchangeProposal approved = proposal.withStatus(DutyExchangeStatus.APPROVED);
        exchangeRepository.save(approved, "Затверджено адміністратором (" + adminUsername + "): "
                        + proposal.initiatorName() + " ↔ " + proposal.counterpartName(),
                adminUsername, adminUsername + "@duty.local");
        return approved;
    }

    /** Адміністратор відхиляє: {@code ACCEPTED} → {@code REJECTED}, графік не чіпається. */
    public DutyExchangeProposal reject(int id, String adminUsername) {
        DutyExchangeProposal proposal = requireProposal(id);
        requireStatus(proposal, DutyExchangeStatus.ACCEPTED);

        DutyExchangeProposal rejected = proposal.withStatus(DutyExchangeStatus.REJECTED);
        exchangeRepository.save(rejected, "Відхилено адміністратором (" + adminUsername + "): "
                        + proposal.initiatorName() + " ↔ " + proposal.counterpartName(),
                adminUsername, adminUsername + "@duty.local");
        return rejected;
    }

    /** Прибирає завершену пропозицію зі списку — після того, як сторона побачила банер із результатом. */
    public void acknowledge(int id, String actingUsername, String actingEngineerName) {
        DutyExchangeProposal proposal = requireProposal(id);
        if (!isParty(proposal, actingEngineerName)) {
            throw new DutyExchangeValidationException("Ця дія не для вас");
        }
        if (proposal.status().active()) {
            throw new DutyExchangeValidationException("Пропозицію #" + id + " ще не вирішено");
        }
        exchangeRepository.delete(id, "Переглянуто (" + actingEngineerName + "): пропозиція #" + id,
                actingUsername, actingUsername + "@duty.local");
    }

    /** Гачок для {@code ScheduleGenerationController.delete()} — анулює все, що спиралось на щойно зниклі місяці. */
    public void revalidateAll() {
        for (DutyExchangeProposal proposal : exchangeRepository.findAll()) {
            if (proposal.status().active()) {
                checkpoint(proposal);
            }
        }
    }

    /**
     * Перевіряє пропозицію проти ПОТОЧНОГО графіка перед кожним переходом
     * стану, який щось міняє. Пропозиція живе між запитами (іноді днями), і за
     * цей час місяць могли перегенерувати, видалити чи просто переставити
     * позначки — застосовувати її «наосліп» не можна.
     *
     * @return ту саму пропозицію, або вже збережену як {@code STALE_CANCELLED}
     */
    private DutyExchangeProposal checkpoint(DutyExchangeProposal proposal) {
        if (isStillValid(proposal)) {
            return proposal;
        }
        DutyExchangeProposal cancelled = proposal.withStatus(DutyExchangeStatus.STALE_CANCELLED);
        exchangeRepository.save(cancelled,
                "Анульовано автоматично: графік змінився після створення пропозиції #" + proposal.id(),
                SYSTEM_USERNAME, SYSTEM_USERNAME + "@duty.local");
        return cancelled;
    }

    /**
     * Чи ще застосовна пропозиція: усі згадані місяці на місці й усі кроки
     * проходять ту саму перевірку, що й при створенні (себе саму при цьому не
     * враховуючи — інакше вона б «блокувала» власні дати).
     */
    private boolean isStillValid(DutyExchangeProposal proposal) {
        for (YearMonth month : proposal.referencedMonths()) {
            if (!scheduleRepository.exists(month)) {
                return false;
            }
        }
        try {
            validateProposal(proposal.initiatorName(), proposal.counterpartName(), proposal.steps(), proposal.id());
            return true;
        } catch (DutyExchangeValidationException e) {
            return false;
        }
    }

    /**
     * Усі правила обміну для набору кроків одразу.
     *
     * @param excludeProposalId пропозиція, чиї власні дати не рахувати зайнятими
     *        (перевірка пропозиції на саму себе), або {@code null} для нової
     * @throws DutyExchangeValidationException на першому ж порушенні
     */
    private void validateProposal(String initiatorName, String counterpartName, List<DutyExchangeStep> steps,
                                   Integer excludeProposalId) {
        if (initiatorName.equals(counterpartName)) {
            throw new DutyExchangeValidationException("Не можна пропонувати обмін самому собі");
        }
        List<DutyExchangeProposal> existing = exchangeRepository.findAll();
        Set<LocalDate> initiatorLocked = lockedDates(initiatorName, excludeProposalId, existing);
        Set<LocalDate> counterpartLocked = lockedDates(counterpartName, excludeProposalId, existing);
        // Кроки однієї пропозиції майже завжди в одному-двох місяцях —
        // читаємо кожен файл графіка один раз на всю перевірку, а не по
        // два рази на КОЖЕН крок, як було доти.
        Map<YearMonth, DutySchedule> schedules = new LinkedHashMap<>();
        for (DutyExchangeStep step : steps) {
            validateStep(initiatorName, counterpartName, step, initiatorLocked, counterpartLocked, schedules);
        }
    }

    /**
     * Правила одного кроку: обидві дати в майбутньому, жодна не задіяна в
     * іншій активній пропозиції, і обидві клітинки придатні до обміну.
     */
    private void validateStep(String initiatorName, String counterpartName, DutyExchangeStep step,
                               Set<LocalDate> initiatorLocked, Set<LocalDate> counterpartLocked,
                               Map<YearMonth, DutySchedule> schedules) {
        LocalDate today = LocalDate.now();
        if (!step.initiatorDate().isAfter(today) || !step.counterpartDate().isAfter(today)) {
            throw new DutyExchangeValidationException("Дати обміну мають бути в майбутньому");
        }
        if (initiatorLocked.contains(step.initiatorDate())) {
            throw new DutyExchangeValidationException("Дата " + step.initiatorDate() + " вже задіяна в іншій пропозиції");
        }
        if (counterpartLocked.contains(step.counterpartDate())) {
            throw new DutyExchangeValidationException("Дата " + step.counterpartDate() + " вже задіяна в іншій пропозиції");
        }
        checkCell(initiatorName, step.initiatorDate(), step.type(), counterpartName, schedules);
        checkCell(counterpartName, step.counterpartDate(), step.type(), initiatorName, schedules);
    }

    /** На {@code date}: у {@code ownerName} має стояти саме {@code expectedType}, а "хрестова" клітинка {@code otherName} — безпечна для передачі (W/OFF, не O/I/S). */
    private void checkCell(String ownerName, LocalDate date, DutyMark expectedType, String otherName,
                            Map<YearMonth, DutySchedule> schedules) {
        DutySchedule schedule = scheduleAt(schedules, YearMonth.from(date));
        Engineer owner = requireRotationEngineer(schedule, ownerName);
        Engineer other = requireRotationEngineer(schedule, otherName);
        DutyDay day = requireDay(schedule, date.getDayOfMonth());

        DutyMark ownerMark = day.markFor(owner.number());
        if (ownerMark != expectedType) {
            throw new DutyExchangeValidationException(ownerName + " " + date + ": очікувалась позначка "
                    + expectedType.displayLetter() + ", а стоїть " + ownerMark.displayLetter());
        }
        DutyMark otherMark = day.markFor(other.number());
        if (otherMark != DutyMark.WORK && otherMark != DutyMark.OFF) {
            throw new DutyExchangeValidationException(otherName + " " + date + ": не можна обмінювати — там "
                    + otherMark.displayName().toLowerCase());
        }
    }

    /**
     * Дати {@code engineerName}, уже задіяні в іншій активній пропозиції.
     * Список пропозицій приймається готовим, а не читається тут: викликач
     * зазвичай перевіряє відразу обидві сторони обміну, і кожне таке
     * читання — повний обхід каталогу з розбором усіх файлів.
     *
     * @param excludeProposalId пропозиція, яку не враховувати (перевірка
     *                          пропозиції на саму себе при повторній
     *                          валідації), або {@code null}
     */
    private Set<LocalDate> lockedDates(String engineerName, Integer excludeProposalId,
                                        List<DutyExchangeProposal> proposals) {
        Set<LocalDate> locked = new HashSet<>();
        for (DutyExchangeProposal proposal : proposals) {
            if (!proposal.status().active()) {
                continue;
            }
            if (excludeProposalId != null && proposal.id() == excludeProposalId) {
                continue;
            }
            for (DutyExchangeStep step : proposal.steps()) {
                if (proposal.initiatorName().equals(engineerName)) {
                    locked.add(step.initiatorDate());
                }
                if (proposal.counterpartName().equals(engineerName)) {
                    locked.add(step.counterpartDate());
                }
            }
        }
        return locked;
    }

    /**
     * Застосовує пропозицію до графіка: спершу міняє позначки в пам'яті по
     * всіх зачеплених місяцях, потім записує їх і комітить ОДНИМ комітом —
     * щоб обмін між двома різними місяцями не міг лишитись застосованим
     * наполовину. Автор коміту — завжди ініціатор, а не той, хто затвердив.
     */
    private void applyToSchedule(DutyExchangeProposal proposal) {
        Map<YearMonth, DutySchedule> working = new LinkedHashMap<>();
        for (DutyExchangeStep step : proposal.steps()) {
            swapOnDate(working, step.initiatorDate(), proposal.initiatorName(), proposal.counterpartName());
            swapOnDate(working, step.counterpartDate(), proposal.initiatorName(), proposal.counterpartName());
        }

        List<Path> touchedFiles = new ArrayList<>();
        for (var entry : working.entrySet()) {
            Path file = scheduleRepository.fileFor(entry.getKey());
            try {
                Files.writeString(file, DutyScheduleFormat.serialize(entry.getValue()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write " + file, e);
            }
            touchedFiles.add(file);
        }
        String message = "Обмін чергуваннями: " + proposal.initiatorName() + " ↔ " + proposal.counterpartName()
                + " (пропозиція #" + proposal.id() + ")";
        gitCommitService.commit(scheduleRepository.dataDir(), touchedFiles, message,
                proposal.initiatorUsername(), proposal.initiatorUsername() + "@duty.local");
    }

    /**
     * Міняє місцями позначки двох інженерів на одну дату, накопичуючи змінені
     * графіки у {@code working} — там же їх потім бере {@link #applyToSchedule} на запис.
     */
    private void swapOnDate(Map<YearMonth, DutySchedule> working, LocalDate date, String initiatorName, String counterpartName) {
        YearMonth month = YearMonth.from(date);
        DutySchedule schedule = scheduleAt(working, month);
        int initiatorNumber = requireRotationEngineer(schedule, initiatorName).number();
        int counterpartNumber = requireRotationEngineer(schedule, counterpartName).number();
        working.put(month, withSwappedMarks(schedule, date.getDayOfMonth(), initiatorNumber, counterpartNumber));
    }

    /**
     * Копія графіка, у якій один день замінено на день зі зміненими позначками
     * (записи незмінні — правити на місці нічого не можна).
     */
    private DutySchedule withSwappedMarks(DutySchedule schedule, int dayOfMonth, int engineerA, int engineerB) {
        List<DutyDay> newDays = schedule.days().stream()
                .map(d -> d.day() != dayOfMonth ? d : swapMarksOnDay(d, engineerA, engineerB))
                .toList();
        return new DutySchedule(schedule.month(), schedule.engineers(), newDays, schedule.lastDays(), schedule.tid());
    }

    /**
     * Копія дня, де позначки двох інженерів помінялись місцями цілком. Саме
     * «цілком», а не «віддав D»: те, що стояло в колеги (найчастіше {@code OFF},
     * рідше {@code WORK}), переходить ініціатору — звідси й «кілька вихідних
     * поспіль без втрати робочих годин».
     */
    private DutyDay swapMarksOnDay(DutyDay day, int engineerA, int engineerB) {
        Map<Integer, DutyMark> marks = new LinkedHashMap<>(day.marks());
        DutyMark markA = day.markFor(engineerA);
        DutyMark markB = day.markFor(engineerB);
        marks.put(engineerA, markB);
        marks.put(engineerB, markA);
        return new DutyDay(day.day(), day.dow(), day.holiday(), marks);
    }

    /**
     * Графік місяця з переданого знімка, дочитуючи його з диска лише при
     * першому зверненні. Спільний і для перевірки кроків
     * ({@link #checkCell}), і для їх застосування ({@link #swapOnDate}) —
     * там знімок ще й накопичує вже змінені графіки, які потім ідуть на
     * запис одним комітом.
     */
    private DutySchedule scheduleAt(Map<YearMonth, DutySchedule> schedules, YearMonth month) {
        return schedules.computeIfAbsent(month, m -> scheduleRepository.find(m)
                .orElseThrow(() -> new DutyExchangeValidationException("Немає графіка за " + m)));
    }

    /** Інженер за П.І.Б., якщо він у цьому місяці бере участь у ротації. */
    private Optional<Engineer> rotationEngineer(DutySchedule schedule, String name) {
        return schedule.engineers().stream()
                .filter(e -> e.name().equals(name) && !e.onlyWorkdays())
                .findFirst();
    }

    /**
     * Те саме, але обов'язково.
     *
     * @throws DutyExchangeValidationException якщо його в цьому місяці нема або
     *         він позначений «лише робочі дні» (ростер міг змінитись)
     */
    private Engineer requireRotationEngineer(DutySchedule schedule, String name) {
        return rotationEngineer(schedule, name)
                .orElseThrow(() -> new DutyExchangeValidationException(
                        name + " не бере участі в ротації чергувань у " + schedule.month()));
    }

    /**
     * День місяця з графіка.
     *
     * @throws DutyExchangeValidationException якщо такого дня у файлі нема (пошкоджені дані)
     */
    private DutyDay requireDay(DutySchedule schedule, int dayOfMonth) {
        return schedule.days().stream()
                .filter(d -> d.day() == dayOfMonth)
                .findFirst()
                .orElseThrow(() -> new DutyExchangeValidationException("Немає дня " + dayOfMonth + " у " + schedule.month()));
    }

    /**
     * Пропозиція за номером.
     *
     * @throws DutyExchangeValidationException якщо її нема — зокрема й тоді, коли
     *         її щойно «визнали» (кнопка «Зрозуміло») з іншої вкладки
     */
    private DutyExchangeProposal requireProposal(int id) {
        return exchangeRepository.find(id)
                .orElseThrow(() -> new DutyExchangeValidationException("Немає пропозиції #" + id));
    }

    /**
     * Пропозиція має бути саме в цьому стані — захист від повторного
     * натискання й від дії з несвіжої сторінки.
     */
    private void requireStatus(DutyExchangeProposal proposal, DutyExchangeStatus expected) {
        if (proposal.status() != expected) {
            throw new DutyExchangeValidationException("Пропозиція #" + proposal.id() + " зараз не в стані " + expected);
        }
    }

    /**
     * Дію виконує саме той, кому вона адресована. Це не дублює
     * {@code SecurityConfig}: там ролі, а тут — конкретна сторона конкретної
     * пропозиції, чого URL-матчер знати не може.
     */
    private void requireParty(String expectedName, String actingEngineerName) {
        if (!expectedName.equals(actingEngineerName)) {
            throw new DutyExchangeValidationException("Ця дія не для вас");
        }
    }
}
