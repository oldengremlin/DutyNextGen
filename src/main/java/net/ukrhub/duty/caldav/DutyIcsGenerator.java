package net.ukrhub.duty.caldav;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Порт {@code duty2ics.pl}: перетворює графік місяця на список ICS-подій —
 * по одній на кожну активну позначку (D/W/O/I/S) кожного інженера, від
 * сьогодні й далі (минуле навмисно не публікуємо — застарілий
 * {@code duty-caldav-sync} ним і так ніколи не керує). UID детермінований
 * (дата + номер інженера), тож повторна генерація на той самий день дає
 * той самий UID — основа ідемпотентності {@link CalDavSyncService}.
 *
 * <p>Формат UID ({@code duty-YYYYMMDD-NUM@duty.ukrhub.net}) і вміст подій
 * (час чергування 8:00–20:00, робочого дня 9:00–17:00/17:30 з коротшою
 * п'ятницею) свідомо зберігають той самий вигляд, що й у застарілого
 * скрипту, — щоб продовжити, а не задублювати, уже опубліковані в Baikal
 * події. Позначка "S" (сесія) — розширення nextgen, якого не було в
 * оригіналі; трактуємо як подію на весь день, за тим самим принципом,
 * що й відпустку/лікарняний.
 */
public final class DutyIcsGenerator {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private DutyIcsGenerator() {
    }

    public static List<IcsEvent> generate(DutySchedule schedule, LocalDate today) {
        List<IcsEvent> events = new ArrayList<>();
        for (DutyDay day : schedule.days()) {
            LocalDate date = schedule.month().atDay(day.day());
            if (date.isBefore(today)) {
                continue;
            }
            for (Engineer engineer : schedule.engineers()) {
                DutyMark mark = day.markFor(engineer.number());
                if (mark == DutyMark.OFF) {
                    continue;
                }
                events.add(eventFor(date, engineer, mark));
            }
        }
        return events;
    }

    private static IcsEvent eventFor(LocalDate date, Engineer engineer, DutyMark mark) {
        String uid = "duty-" + YMD.format(date) + "-" + engineer.number() + "@duty.ukrhub.net";
        String dtstamp = YMD.format(date) + "T000000Z";

        String body = switch (mark) {
            case DUTY -> timedEvent(uid, dtstamp, date, 8, 0, 20, 0, "Чергування, " + engineer.name());
            case WORK -> {
                boolean friday = date.getDayOfWeek() == DayOfWeek.FRIDAY;
                yield timedEvent(uid, dtstamp, date, 9, 0, 17, friday ? 0 : 30, "Робочий день, " + engineer.name());
            }
            case VACATION -> allDayEvent(uid, dtstamp, date, "Відпустка, " + engineer.name());
            case SICK -> allDayEvent(uid, dtstamp, date, "Лікарняний, " + engineer.name());
            case SESSION -> allDayEvent(uid, dtstamp, date, "Сесія, " + engineer.name());
            case OFF -> throw new IllegalStateException("OFF не публікується — фільтрується в generate()");
        };

        return new IcsEvent(uid, date, body);
    }

    private static String timedEvent(String uid, String dtstamp, LocalDate date,
                                      int startHour, int startMin, int endHour, int endMin, String summary) {
        String dtstart = YMD.format(date) + "T" + hhmm(startHour, startMin) + "00";
        String dtend = YMD.format(date) + "T" + hhmm(endHour, endMin) + "00";
        return vcalendar(uid, dtstamp, "DTSTART:" + dtstart, "DTEND:" + dtend, summary);
    }

    private static String allDayEvent(String uid, String dtstamp, LocalDate date, String summary) {
        String dtstart = YMD.format(date);
        String dtend = YMD.format(date.plusDays(1));
        return vcalendar(uid, dtstamp, "DTSTART;VALUE=DATE:" + dtstart, "DTEND;VALUE=DATE:" + dtend, summary);
    }

    private static String vcalendar(String uid, String dtstamp, String dtstartLine, String dtendLine, String summary) {
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//UKRCOM//duty2ics//UK\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "DTSTAMP:" + dtstamp + "\r\n"
                + dtstartLine + "\r\n"
                + dtendLine + "\r\n"
                + "SUMMARY:" + summary + "\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private static String hhmm(int hour, int minute) {
        return "%02d%02d".formatted(hour, minute);
    }
}
