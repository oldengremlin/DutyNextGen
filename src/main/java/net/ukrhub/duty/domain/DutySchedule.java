package net.ukrhub.duty.domain;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Графік чергувань на один місяць — те, що зберігається в одному файлі
 * {@code data/duty/YYYYMM}.
 *
 * <p>{@code lastDay0}/{@code lastDay1} — позначки двох останніх днів цього
 * місяця, потрібні лише для того, щоб генератор наступного місяця (порт
 * tds.pl) знав, з якої фази ротаційного шаблону продовжувати. Для самого
 * поточного місяця вони інформаційної ролі не відіграють.
 */
public record DutySchedule(
        YearMonth month,
        List<Engineer> engineers,
        List<DutyDay> days,
        Map<Integer, DutyMark> lastDay0,
        Map<Integer, DutyMark> lastDay1
) {

    public Engineer engineer(int number) {
        return engineers.stream()
                .filter(e -> e.number() == number)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Немає адміністратора №" + number));
    }
}
