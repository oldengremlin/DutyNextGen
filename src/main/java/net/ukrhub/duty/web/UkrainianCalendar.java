package net.ukrhub.duty.web;

import java.time.DayOfWeek;
import java.time.Month;

/**
 * Українські назви місяців і скорочення днів тижня для відображення.
 * Захардкоджено навмисно, а не через {@code java.time} + {@code Locale}
 * "uk" — невеликий фіксований список, і так надійніше та не залежить
 * від того, наскільки повні дані CLDR в конкретній збірці JDK.
 */
public final class UkrainianCalendar {

    private static final String[] MONTHS = {
            "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
            "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
    };

    private static final String[] DOW_SHORT = {
            "Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"
    };

    private UkrainianCalendar() {
    }

    public static String monthName(Month month) {
        return MONTHS[month.getValue() - 1];
    }

    public static String dayOfWeekShort(DayOfWeek dow) {
        // dow буває null для рядка графіка, який не вдалося розпізнати
        // (пошкоджені/нестандартні дані) — краще показати "?", ніж
        // упустити всю сторінку через один битий рядок.
        return dow == null ? "?" : DOW_SHORT[dow.getValue() - 1];
    }
}
