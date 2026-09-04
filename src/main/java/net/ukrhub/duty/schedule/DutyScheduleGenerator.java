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
package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.domain.RotationTemplate;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Порт ротаційного алгоритму {@code tds.pl} застарілого проєкту, узагальнений
 * на довільний {@link RotationTemplate} (K ротаційних слотів, а не жорстко
 * зашиті 2): генерує графік місяця, наступного за переданим
 * ({@code from.month().plusMonths(1)}), продовжуючи ротацію чергових з того
 * місця, де вона зупинилась.
 *
 * <p><b>Мапінг "слот шаблону → адміністратор".</b> Найпростіший можливий:
 * ротаційні адміністратори (без ознаки {@link Engineer#onlyWorkdays()}),
 * відсортовані за {@link Engineer#number()}, зіставляються зі слотами
 * шаблону в тому самому порядку (слот 0 — перший за номером, і так далі).
 * Це свідомий вибір, не тимчасова заглушка: саме так неявно й працював
 * оригінальний алгоритм для двох чергових, і продовжувати покладатись на
 * номер адміністратора як на стабільний ідентифікатор — найпростіше
 * рішення, що не потребує окремого сховища для явної відповідності
 * (див. {@code docs/rotation-templates.md}).
 *
 * <p><b>Продовження фази.</b> {@link DutySchedule#lastDays()} — K останніх
 * днів ФАКТИЧНОГО стану ротації (не лічильник алгоритму: чергові можуть
 * самі мінятися днями всередині місяця). Генератор шукає в шаблоні (циклічно
 * розтягнутому на потрібну довжину — сам період не обов'язково мінімальний,
 * та це і не важливо: пошук шукає, з якої позиції в межах ОДНОГО періоду ці
 * K днів ідуть поспіль) — знайдена фаза й визначає ротацію на наступний
 * місяць, день за днем.
 *
 * <p>Якщо вхідні дані не відповідають передумовам (кількість чергових не
 * збігається з {@link RotationTemplate#slots()}, чи фазу не вдалось
 * визначити за {@code lastDays()}) — {@link ScheduleGenerationException} з
 * поясненням, а не мовчазне вгадування: ротаційний графік чергувань —
 * production-дані, якими користуються з 2008 року.
 */
public final class DutyScheduleGenerator {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private DutyScheduleGenerator() {
    }

    /**
     * Генерує наступний місяць, продовжуючи фазу {@code template} від
     * {@code from.lastDays()}. Не пошук фази, а точний зсув у шаблоні —
     * {@link #generateFromOffset}: коли перехід на цей шаблон лише
     * починається (ще нема фактичного хвоста, сумісного саме з ним),
     * викликач (веб-шар) явно питає користувача, з якого дня періоду
     * почати, замість спроби вгадати фазу.
     */
    public static DutySchedule generateNext(DutySchedule from, RotationTemplate template) {
        List<Engineer> engineers = sortedEngineers(from);
        List<Engineer> rotating = rotatingOf(engineers);
        requireSlotsMatch(from, template, rotating);

        List<Map<Integer, DutyMark>> tail = from.lastDays();
        if (tail.size() != template.slots()) {
            throw new ScheduleGenerationException(
                    "Кількість збережених останніх днів (" + tail.size() + ") графіка " + YM.format(from.month())
                            + " не збігається з кількістю слотів шаблону «" + template.name() + "» ("
                            + template.slots() + ") — продовжити фазу неможливо, згенеруй з явним зсувом.");
        }

        int period = template.period();
        String[] columns = dayColumns(template, period + 32);

        int offset = findPhase(tail, rotating, columns, period);
        if (offset < 0) {
            throw new ScheduleGenerationException(
                    "Не вдалося визначити фазу шаблону «" + template.name() + "» за останніми днями графіка "
                            + YM.format(from.month()) + " ([ LastDay0 ] і далі) — перевір ці розділи у файлі "
                            + "графіка, або згенеруй з явним зсувом (вибором дня періоду).");
        }

        return generate(from, engineers, rotating, template, columns, offset);
    }

    /**
     * Генерує наступний місяць з явно заданого зсуву в періоді шаблону
     * (0-based: 0 — день 1 нового місяця збігається з першим днем періоду
     * шаблону) — без пошуку фази. Використовується, коли адміністратор
     * явно обирає, з якого дня шаблону почати (перехід на інший шаблон,
     * зміна кількості чергових): {@code docs/rotation-templates.md}.
     */
    public static DutySchedule generateFromOffset(DutySchedule from, RotationTemplate template, int startOffset) {
        List<Engineer> engineers = sortedEngineers(from);
        List<Engineer> rotating = rotatingOf(engineers);
        requireSlotsMatch(from, template, rotating);

        int period = template.period();
        String[] columns = dayColumns(template, period + 32);
        // offset передається як позиція ПЕРШОГО дня нового місяця; findPhase-подібні
        // методи нижче індексують "останній збіжний день", тож віднімаємо один крок.
        int offset = ((startOffset % period) + period) % period - 1;

        return generate(from, engineers, rotating, template, columns, offset);
    }

    private static List<Engineer> sortedEngineers(DutySchedule from) {
        return from.engineers().stream().sorted(Comparator.comparingInt(Engineer::number)).toList();
    }

    private static List<Engineer> rotatingOf(List<Engineer> engineers) {
        return engineers.stream().filter(e -> !e.onlyWorkdays()).toList();
    }

    /**
     * Номер адміністратора → його слот у шаблоні. Порядок той самий, що й
     * у {@code rotating} (за {@link Engineer#number()}) — просто заздалегідь
     * і один раз, замість {@code rotating.indexOf(e)} усередині подвійного
     * циклу «кожен день × кожен адміністратор».
     */
    private static Map<Integer, Integer> slotByNumber(List<Engineer> rotating) {
        Map<Integer, Integer> slots = new LinkedHashMap<>();
        for (int slot = 0; slot < rotating.size(); slot++) {
            slots.put(rotating.get(slot).number(), slot);
        }
        return slots;
    }

    private static void requireSlotsMatch(DutySchedule from, RotationTemplate template, List<Engineer> rotating) {
        if (rotating.size() != template.slots()) {
            throw new ScheduleGenerationException(
                    "Шаблон «" + template.name() + "» розрахований на " + template.slots()
                            + " чергових, а в графіку " + YM.format(from.month()) + " їх " + rotating.size()
                            + " (без ознаки «лише робочі дні») — обери інший шаблон або зміни ростер.");
        }
    }

    /**
     * @param offset позиція в {@code columns} останнього дня, що збігається з
     *               {@code from.lastDays()} (або еквівалент при явному зсуві) —
     *               день 1 нового місяця відповідає {@code columns[offset + 1]}.
     */
    private static DutySchedule generate(DutySchedule from, List<Engineer> engineers, List<Engineer> rotating,
                                          RotationTemplate template, String[] columns, int offset) {
        YearMonth target = from.month().plusMonths(1);
        int daysInMonth = target.lengthOfMonth();
        List<DutyDay> days = new ArrayList<>(daysInMonth);

        // Ковзне вікно двох щойно опублікованих днів на кожного чергового —
        // для правила понеділка нижче; засіяне фактичними останніми днями
        // попереднього місяця (edge case: 1 чи 2 число нового місяця сам —
        // понеділок, і субота/неділя перед ним ще в попередньому місяці).
        List<DutyDay> previousDays = from.days();
        Map<Integer, DutyMark> twoDaysAgo = previousDays.size() >= 2
                ? previousDays.get(previousDays.size() - 2).marks() : null;
        Map<Integer, DutyMark> oneDayAgo = previousDays.size() >= 1
                ? previousDays.get(previousDays.size() - 1).marks() : null;

        Map<Integer, Integer> slotByNumber = slotByNumber(rotating);
        for (int day = 1; day <= daysInMonth; day++) {
            DayOfWeek dow = target.atDay(day).getDayOfWeek();
            Map<Integer, DutyMark> marks = marksForDay(engineers, slotByNumber, dow, columns[offset + day]);
            if (dow == DayOfWeek.MONDAY) {
                applyWeekendDutyOverride(marks, rotating, twoDaysAgo, oneDayAgo);
            }
            days.add(new DutyDay(day, dow, false, marks));
            twoDaysAgo = oneDayAgo;
            oneDayAgo = marks;
        }

        // Найстаріший день тижня — перший елемент, найновіший (справжній
        // останній день місяця) — останній: та сама конвенція, що й у
        // [ LastDay0 ]/[ LastDay1 ] застарілого формату (LastDay1 — це
        // насправді останній день, LastDay0 — день перед ним), і той
        // самий порядок, якого очікує пошук фази (findPhase).
        List<Map<Integer, DutyMark>> newTail = new ArrayList<>(template.slots());
        for (int i = template.slots() - 1; i >= 0; i--) {
            newTail.add(rawMarksAt(engineers, slotByNumber, columns[offset + daysInMonth - i]));
        }

        return new DutySchedule(target, engineers, days, newTail, template.id());
    }

    /**
     * Обов'язкове правило (не залежить від того, чи період шаблону кратний
     * 7): якщо черговий чергував (D) у суботу чи неділю, у понеділок його
     * "робочий день" (W) з шаблону скасовується — понеділок стає вихідним.
     * Свідомо на відміну від {@link #rawMarksAt} — тут це реальна виправлена
     * позначка, яку бачить користувач, а не сирий шаблон для продовження
     * фази: та сама логіка, що й у правила "вихідний → W випадає" вище
     * (calendar-driven), але тут ще й content-driven (дивиться на
     * позначку попереднього дня, а не лише на сьогоднішній день тижня).
     * Якщо черговий сам домовився й помінявся днями — це поза межами коду.
     */
    private static void applyWeekendDutyOverride(Map<Integer, DutyMark> mondayMarks, List<Engineer> rotating,
                                                  Map<Integer, DutyMark> saturdayMarks, Map<Integer, DutyMark> sundayMarks) {
        for (Engineer e : rotating) {
            if (mondayMarks.get(e.number()) != DutyMark.WORK) {
                continue;
            }
            boolean dutyOnSaturday = saturdayMarks != null && saturdayMarks.get(e.number()) == DutyMark.DUTY;
            boolean dutyOnSunday = sundayMarks != null && sundayMarks.get(e.number()) == DutyMark.DUTY;
            if (dutyOnSaturday || dutyOnSunday) {
                mondayMarks.put(e.number(), DutyMark.OFF);
            }
        }
    }

    /**
     * Циклічно розтягнутий шаблон — по одному K-символьному рядку на день
     * (символ на позицію = слот, у порядку {@link RotationTemplate#rows()}),
     * довжиною щонайменше {@code minLength}. Період не обов'язково
     * мінімальний — довший чи коротший, алгоритму байдуже, доки він
     * достатньо довгий, щоб вистачило на пошук фази (у межах одного
     * періоду) плюс найдовший можливий місяць.
     */
    private static String[] dayColumns(RotationTemplate template, int minLength) {
        int period = template.period();
        int length = Math.max(minLength, period);
        String[] columns = new String[length];
        for (int d = 0; d < length; d++) {
            int posInPeriod = d % period;
            StringBuilder sb = new StringBuilder(template.slots());
            for (String row : template.rows()) {
                sb.append(row.charAt(posInPeriod));
            }
            columns[d] = sb.toString();
        }
        return columns;
    }

    /** Позиція (у межах одного періоду) останнього дня з {@code tail}, або -1, якщо фазу не вдалось визначити. */
    private static int findPhase(List<Map<Integer, DutyMark>> tail, List<Engineer> rotating, String[] columns, int period) {
        int k = tail.size();
        String[] wanted = tail.stream().map(marks -> joinMarks(marks, rotating)).toArray(String[]::new);
        for (int start = period - 1; start >= 0; start--) {
            boolean matches = true;
            for (int i = 0; i < k; i++) {
                if (!columns[start + i].equals(wanted[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return start + k - 1;
            }
        }
        return -1;
    }

    private static String joinMarks(Map<Integer, DutyMark> marks, List<Engineer> rotating) {
        StringBuilder sb = new StringBuilder(rotating.size());
        for (Engineer e : rotating) {
            DutyMark mark = marks.get(e.number());
            sb.append(mark != null ? mark.code() : '-');
        }
        return sb.toString();
    }

    /** Позначки на конкретний день: onlyWorkdays — W/вихідний за днем тижня, чергові — за шаблоном ({@code slotByNumber}). */
    private static Map<Integer, DutyMark> marksForDay(List<Engineer> engineers, Map<Integer, Integer> slotByNumber,
                                                        DayOfWeek dow, String column) {
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            DutyMark mark;
            if (e.onlyWorkdays()) {
                mark = weekend ? DutyMark.OFF : DutyMark.WORK;
            } else {
                mark = DutyMark.fromChar(column.charAt(slotByNumber.get(e.number())));
                if (weekend && mark == DutyMark.WORK) {
                    mark = DutyMark.OFF;
                }
            }
            marks.put(e.number(), mark);
        }
        return marks;
    }

    /**
     * Позначки для нового {@code lastDays}: без коригування на вихідний
     * (сирі значення шаблону) — onlyWorkdays завжди "-" (як і раніше), бо
     * для наступної генерації ці позначки все одно ігноруються.
     */
    private static Map<Integer, DutyMark> rawMarksAt(List<Engineer> engineers, Map<Integer, Integer> slotByNumber,
                                                       String column) {
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            if (e.onlyWorkdays()) {
                marks.put(e.number(), DutyMark.OFF);
            } else {
                marks.put(e.number(), DutyMark.fromChar(column.charAt(slotByNumber.get(e.number()))));
            }
        }
        return marks;
    }
}
