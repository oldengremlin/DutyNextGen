package net.ukrhub.duty.domain;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * Один день місяця з позначками кожного адміністратора.
 *
 * @param day     число місяця (1..31)
 * @param dow     день тижня
 * @param holiday державне свято/особливий день — у файлі позначається `*`
 *                одразу після скорочення дня тижня (напр. {@code 1  Th*}),
 *                успадковано з застарілого формату (index.pl: колір "re")
 * @param marks   позначка по кожному адміністратору, ключ — {@link Engineer#number()}
 */
public record DutyDay(int day, DayOfWeek dow, boolean holiday, Map<Integer, DutyMark> marks) {

    public DutyDay(int day, DayOfWeek dow, Map<Integer, DutyMark> marks) {
        this(day, dow, false, marks);
    }

    public boolean isWeekend() {
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    public DutyMark markFor(int engineerNumber) {
        return marks.getOrDefault(engineerNumber, DutyMark.OFF);
    }
}
