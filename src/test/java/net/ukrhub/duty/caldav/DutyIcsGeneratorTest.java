package net.ukrhub.duty.caldav;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DutyIcsGeneratorTest {

    private static DutySchedule scheduleWithMarks(Map<Integer, DutyMark> marksForDay1, DayOfWeek dow) {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Іванов І.", false),
                new Engineer(2, "Петров П.", false)
        );
        List<DutyDay> days = List.of(new DutyDay(1, dow, false, marksForDay1));
        return new DutySchedule(YearMonth.of(2033, 3), engineers, days, Map.of(), Map.of());
    }

    @Test
    void dutyMarkProducesEightToTwentyEvent() {
        DutySchedule schedule = scheduleWithMarks(Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF), DayOfWeek.TUESDAY);

        List<IcsEvent> events = DutyIcsGenerator.generate(schedule, LocalDate.of(2033, 3, 1));

        assertThat(events).hasSize(1);
        IcsEvent event = events.get(0);
        assertThat(event.uid()).isEqualTo("duty-20330301-1@duty.ukrhub.net");
        assertThat(event.date()).isEqualTo(LocalDate.of(2033, 3, 1));
        assertThat(event.body())
                .contains("UID:duty-20330301-1@duty.ukrhub.net\r\n")
                .contains("DTSTART:20330301T080000\r\n")
                .contains("DTEND:20330301T200000\r\n")
                .contains("SUMMARY:Чергування, Іванов І.\r\n")
                .startsWith("BEGIN:VCALENDAR\r\n")
                .endsWith("END:VCALENDAR\r\n");
    }

    @Test
    void workMarkEndsAtSeventeenThirtyExceptOnFriday() {
        // День тижня генератор бере з реальної календарної дати
        // (schedule.month().atDay(day.day())), а не з поля DutyDay.dow —
        // тож тут потрібні дати, які й справді припадають на ці дні.
        LocalDate tuesday = LocalDate.of(2040, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
        LocalDate friday = LocalDate.of(2040, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));

        String tuesdayBody = DutyIcsGenerator.generate(scheduleForDate(tuesday, DutyMark.WORK), tuesday)
                .get(0).body();
        String fridayBody = DutyIcsGenerator.generate(scheduleForDate(friday, DutyMark.WORK), friday)
                .get(0).body();

        assertThat(tuesdayBody).contains("DTSTART:" + ymd(tuesday) + "T090000\r\n")
                .contains("DTEND:" + ymd(tuesday) + "T173000\r\n");
        assertThat(fridayBody).contains("DTEND:" + ymd(friday) + "T170000\r\n");
    }

    private static DutySchedule scheduleForDate(LocalDate date, DutyMark mark) {
        Engineer engineer = new Engineer(1, "Іванов І.", false);
        DutyDay day = new DutyDay(date.getDayOfMonth(), date.getDayOfWeek(), false, Map.of(1, mark));
        return new DutySchedule(YearMonth.from(date), List.of(engineer), List.of(day), Map.of(), Map.of());
    }

    private static String ymd(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    @Test
    void vacationSickAndSessionProduceAllDayEvents() {
        DutySchedule schedule = scheduleWithMarks(Map.of(1, DutyMark.VACATION, 2, DutyMark.SICK), DayOfWeek.TUESDAY);

        List<IcsEvent> events = DutyIcsGenerator.generate(schedule, LocalDate.of(2033, 3, 1));

        assertThat(events).hasSize(2);
        String vacationBody = events.stream().filter(e -> e.uid().endsWith("-1@duty.ukrhub.net")).findFirst()
                .orElseThrow().body();
        assertThat(vacationBody)
                .contains("DTSTART;VALUE=DATE:20330301\r\n")
                .contains("DTEND;VALUE=DATE:20330302\r\n")
                .contains("SUMMARY:Відпустка, Іванов І.\r\n");

        String sickBody = events.stream().filter(e -> e.uid().endsWith("-2@duty.ukrhub.net")).findFirst()
                .orElseThrow().body();
        assertThat(sickBody).contains("SUMMARY:Лікарняний, Петров П.\r\n");
    }

    @Test
    void sessionMarkIsAllDayEventTooNextgenExtension() {
        DutySchedule schedule = scheduleWithMarks(Map.of(1, DutyMark.SESSION, 2, DutyMark.OFF), DayOfWeek.TUESDAY);

        List<IcsEvent> events = DutyIcsGenerator.generate(schedule, LocalDate.of(2033, 3, 1));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).body())
                .contains("DTSTART;VALUE=DATE:20330301\r\n")
                .contains("SUMMARY:Сесія, Іванов І.\r\n");
    }

    @Test
    void offMarkProducesNoEvent() {
        DutySchedule schedule = scheduleWithMarks(Map.of(1, DutyMark.OFF, 2, DutyMark.OFF), DayOfWeek.TUESDAY);

        assertThat(DutyIcsGenerator.generate(schedule, LocalDate.of(2033, 3, 1))).isEmpty();
    }

    @Test
    void pastDaysAreNeverPublished() {
        DutySchedule schedule = scheduleWithMarks(Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF), DayOfWeek.TUESDAY);

        // "Сьогодні" — 2 березня, а єдиний день у графіку — 1 березня (уже минуле).
        assertThat(DutyIcsGenerator.generate(schedule, LocalDate.of(2033, 3, 2))).isEmpty();
    }
}
