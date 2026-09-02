package net.ukrhub.duty.domain;

import java.time.DayOfWeek;
import java.util.Map;

/**
 * Один день місяця з позначками кожного адміністратора.
 *
 * @param day    число місяця (1..31)
 * @param dow     день тижня
 * @param marks   позначка по кожному адміністратору, ключ — {@link Engineer#number()}
 */
public record DutyDay(int day, DayOfWeek dow, Map<Integer, DutyMark> marks) {

    public boolean isWeekend() {
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    public DutyMark markFor(int engineerNumber) {
        return marks.getOrDefault(engineerNumber, DutyMark.OFF);
    }
}
