package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Порт ротаційного алгоритму {@code tds.pl} застарілого проєкту: генерує
 * графік місяця, наступного за переданим ({@code from.month().plusMonths(1)}),
 * продовжуючи ротацію чергових з того місця, де вона зупинилась.
 *
 * <p><b>Чому саме такий алгоритм.</b> {@code tds.pl} підтримує рівно двох
 * "чергових" адміністраторів (без ознаки {@link Engineer#onlyWorkdays()}) —
 * решта ({@code onlyWorkdays == true}) просто працює в будні (W) і має
 * вихідний у суботу/неділю (без участі в ротації). Черговість двох
 * чергових задається жорстко зашитим у {@code tds.pl} 44-денним базовим
 * шаблоном пар позначок ("D:-" / "-:D"), подвоєним до 88 записів (щоб від
 * будь-якої знайденої фази вистачало запасу на місяць уперед). Продовження
 * ротації з місяця в місяць працює так: в кінці кожного місяця в файл
 * записуються позначки чергових за два останні дні ({@code [ LastDay0 ]} —
 * передостанній, {@code [ LastDay1 ]} — останній). Генератор шукає в
 * шаблоні місце, де ці дві позначки йдуть поспіль — це і є "фаза", з якої
 * рівно продовжується ротація на наступний місяць, день за днем.
 *
 * <p>Це свідомо точний порт, а не спрощення: ротаційний графік чергувань —
 * production-дані, якими користуються з 2008 року, і будь-яке відхилення
 * від оригінальної фази означало б реальний збій графіка чергувань. Якщо
 * вхідні дані не відповідають передумовам алгоритму (не рівно два чергових,
 * чи фазу не вдалось визначити за позначками останніх двох днів) —
 * {@link ScheduleGenerationException} з поясненням, а не мовчазне
 * вгадування.
 */
public final class DutyScheduleGenerator {

    /** Позиція пошуку фази обмежена цим індексом — те саме {@code 41+3} з tds.pl. */
    private static final int PHASE_SEARCH_BOUND = 44;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private static final String[] TEMPLATE = buildTemplate();

    private DutyScheduleGenerator() {
    }

    private static String[] buildTemplate() {
        String[] base = {
                "D:-", "D:-", "-:D", "-:D", "D:-", "D:-", "-:D",
                "-:D", "D:-", "D:-", "-:D", "-:D", "D:-", "D:-",
                "-:D", "-:D", "D:-", "D:-", "-:D", "-:D", "D:-",
                "D:-", "-:D", "-:D", "D:-", "D:-", "-:D", "-:D",
                "D:-", "D:-", "-:D", "-:D", "D:-", "D:-", "-:D",
                "-:D", "D:-", "D:-", "-:D", "-:D", "D:-", "D:-",
                "-:D", "-:D",
        };
        String[] full = new String[base.length * 2];
        System.arraycopy(base, 0, full, 0, base.length);
        System.arraycopy(base, 0, full, base.length, base.length);
        return full;
    }

    public static DutySchedule generateNext(DutySchedule from) {
        YearMonth target = from.month().plusMonths(1);

        List<Engineer> engineers = from.engineers().stream()
                .sorted(Comparator.comparingInt(Engineer::number))
                .toList();
        List<Engineer> rotating = engineers.stream()
                .filter(e -> !e.onlyWorkdays())
                .toList();
        if (rotating.size() != 2) {
            throw new ScheduleGenerationException(
                    "Генератор підтримує рівно двох чергових інженерів (без ознаки «лише робочі дні»), "
                            + "а в графіку " + YM.format(from.month()) + " їх " + rotating.size()
                            + " — генерація наступного місяця можлива лише вручну через редагування.");
        }

        String prevMarks = joinMarks(from.lastDay0(), rotating);
        String lastMarks = joinMarks(from.lastDay1(), rotating);

        int templatePosition = findPhase(prevMarks, lastMarks);
        if (templatePosition < 0) {
            throw new ScheduleGenerationException(
                    "Не вдалося визначити фазу ротаційного шаблону за позначками останніх двох днів графіка "
                            + YM.format(from.month()) + " ([ LastDay0 ] / [ LastDay1 ]) — перевір ці розділи "
                            + "у файлі графіка.");
        }

        int daysInMonth = target.lengthOfMonth();
        List<DutyDay> days = new ArrayList<>(daysInMonth);
        for (int day = 1; day <= daysInMonth; day++) {
            DayOfWeek dow = target.atDay(day).getDayOfWeek();
            days.add(new DutyDay(day, dow, false, marksForDay(engineers, dow, TEMPLATE[templatePosition + day])));
        }

        Map<Integer, DutyMark> lastDay0 = rawMarksAt(engineers, TEMPLATE[templatePosition + daysInMonth - 1]);
        Map<Integer, DutyMark> lastDay1 = rawMarksAt(engineers, TEMPLATE[templatePosition + daysInMonth]);

        return new DutySchedule(target, engineers, days, lastDay0, lastDay1);
    }

    private static int findPhase(String prevMarks, String lastMarks) {
        for (int i = PHASE_SEARCH_BOUND; i >= 1; i--) {
            if (TEMPLATE[i - 1].equals(prevMarks) && TEMPLATE[i].equals(lastMarks)) {
                return i;
            }
        }
        return -1;
    }

    private static String joinMarks(Map<Integer, DutyMark> marks, List<Engineer> rotating) {
        return rotating.stream()
                .map(e -> {
                    DutyMark mark = marks.get(e.number());
                    return mark != null ? String.valueOf(mark.code()) : "-";
                })
                .collect(Collectors.joining(":"));
    }

    /** Позначки на конкретний день: onlyWorkdays — W/вихідний за днем тижня, чергові — за шаблоном. */
    private static Map<Integer, DutyMark> marksForDay(List<Engineer> engineers, DayOfWeek dow, String templateEntry) {
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        String[] position = templateEntry.split(":");
        int posIndex = 0;
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            DutyMark mark;
            if (e.onlyWorkdays()) {
                mark = weekend ? DutyMark.OFF : DutyMark.WORK;
            } else {
                mark = DutyMark.fromChar(position[posIndex].charAt(0));
                if (weekend && mark == DutyMark.WORK) {
                    mark = DutyMark.OFF;
                }
                posIndex++;
            }
            marks.put(e.number(), mark);
        }
        return marks;
    }

    /**
     * Позначки для [ LastDay0 ]/[ LastDay1 ]: без коригування на вихідний
     * (сирі значення шаблону) — onlyWorkdays завжди "-" (як і в tds.pl),
     * бо для наступної генерації ці позначки все одно ігноруються.
     */
    private static Map<Integer, DutyMark> rawMarksAt(List<Engineer> engineers, String templateEntry) {
        String[] position = templateEntry.split(":");
        int posIndex = 0;
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            if (e.onlyWorkdays()) {
                marks.put(e.number(), DutyMark.OFF);
            } else {
                marks.put(e.number(), DutyMark.fromChar(position[posIndex].charAt(0)));
                posIndex++;
            }
        }
        return marks;
    }
}
