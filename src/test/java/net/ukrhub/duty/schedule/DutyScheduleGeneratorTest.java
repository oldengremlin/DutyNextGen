package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.domain.RotationTemplate;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DutyScheduleGeneratorTest {

    /** Той самий мінімальний період, що й колишній вбудований шаблон (D:-/-:D по 2 дні), лише явним {@link RotationTemplate}. */
    private static final RotationTemplate CLASSIC = new RotationTemplate(1, "Класика", List.of("DD--", "--DD"));

    private static DutySchedule validFromSchedule() {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Тільки будні 1", true),
                new Engineer(2, "Черговий 1", false),
                new Engineer(3, "Черговий 2", false),
                new Engineer(4, "Тільки будні 2", true)
        );
        YearMonth month = YearMonth.of(2027, 1);
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.FRIDAY, false,
                Map.of(1, DutyMark.WORK, 2, DutyMark.DUTY, 3, DutyMark.OFF, 4, DutyMark.WORK)));
        Map<Integer, DutyMark> lastDay0 = Map.of(1, DutyMark.OFF, 2, DutyMark.OFF, 3, DutyMark.DUTY, 4, DutyMark.OFF);
        Map<Integer, DutyMark> lastDay1 = Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY, 3, DutyMark.OFF, 4, DutyMark.OFF);
        return new DutySchedule(month, engineers, days, lastDay0, lastDay1);
    }

    @Test
    void rejectsWhenNotExactlyTwoRotatingEngineers() {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Один черговий", false),
                new Engineer(2, "Лише будні", true)
        );
        DutySchedule from = new DutySchedule(YearMonth.of(2027, 1), engineers, List.of(),
                Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF), Map.of(1, DutyMark.OFF, 2, DutyMark.OFF));

        assertThatThrownBy(() -> DutyScheduleGenerator.generateNext(from, CLASSIC))
                .isInstanceOf(ScheduleGenerationException.class)
                .hasMessageContaining("розрахований на");
    }

    @Test
    void rejectsWhenPhaseCannotBeMatched() {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Черговий 1", false),
                new Engineer(2, "Черговий 2", false)
        );
        // Шаблон містить лише "D" і "-" — позначки відпустки/лікарняного
        // на цих двох днях у ньому не зустрічаються за жодної фази.
        DutySchedule from = new DutySchedule(YearMonth.of(2027, 1), engineers, List.of(),
                Map.of(1, DutyMark.VACATION, 2, DutyMark.SICK), Map.of(1, DutyMark.VACATION, 2, DutyMark.SICK));

        assertThatThrownBy(() -> DutyScheduleGenerator.generateNext(from, CLASSIC))
                .isInstanceOf(ScheduleGenerationException.class)
                .hasMessageContaining("Не вдалося визначити фазу");
    }

    @Test
    void generatesNextMonthAndNeverPutsWorkOnWeekendForOnlyWorkdaysEngineer() {
        DutySchedule generated = DutyScheduleGenerator.generateNext(validFromSchedule(), CLASSIC);

        assertThat(generated.month()).isEqualTo(YearMonth.of(2027, 2));
        assertThat(generated.engineers()).hasSize(4);
        assertThat(generated.days()).hasSize(YearMonth.of(2027, 2).lengthOfMonth());

        for (DutyDay day : generated.days()) {
            boolean weekend = day.dow() == DayOfWeek.SATURDAY || day.dow() == DayOfWeek.SUNDAY;

            // "Лише робочі дні" — ніколи W у вихідний.
            if (weekend) {
                assertThat(day.markFor(1)).isEqualTo(DutyMark.OFF);
                assertThat(day.markFor(4)).isEqualTo(DutyMark.OFF);
            } else {
                assertThat(day.markFor(1)).isEqualTo(DutyMark.WORK);
                assertThat(day.markFor(4)).isEqualTo(DutyMark.WORK);
            }

            // Рівно один із двох чергових на "D" щодня — ротація не втрачає фазу.
            long dutyCount = List.of(day.markFor(2), day.markFor(3)).stream()
                    .filter(m -> m == DutyMark.DUTY).count();
            assertThat(dutyCount).isEqualTo(1);
        }
    }

    /**
     * Регресія: production-баг, знайдений на реальних даних — {@code
     * [ LastDayN ] } мають бути впорядковані від найстарішого до
     * найновішого дня (той самий порядок, якого очікує пошук фази й у
     * якому їх завжди писав застарілий формат: {@code LastDay1} — це
     * справжній останній день місяця, а не {@code LastDay0}). Генератор
     * писав їх у зворотному порядку — на симетричному {@code CLASSIC}
     * (обидва слоти рівноцінні) це не ламало жоден інший тест, тож тут
     * звіряємо порядок напряму з реальними позначками останніх двох днів.
     */
    @Test
    void lastDaysAreOrderedOldestToNewestMatchingLegacyFileConvention() {
        DutySchedule generated = DutyScheduleGenerator.generateNext(validFromSchedule(), CLASSIC);

        List<DutyDay> days = generated.days();
        DutyDay lastDay = days.get(days.size() - 1);
        DutyDay dayBeforeLast = days.get(days.size() - 2);

        assertThat(generated.lastDay1().get(2)).isEqualTo(lastDay.markFor(2));
        assertThat(generated.lastDay1().get(3)).isEqualTo(lastDay.markFor(3));
        assertThat(generated.lastDay0().get(2)).isEqualTo(dayBeforeLast.markFor(2));
        assertThat(generated.lastDay0().get(3)).isEqualTo(dayBeforeLast.markFor(3));
    }

    @Test
    void generatedScheduleCanBeChainedIntoFollowingMonth() {
        DutySchedule first = DutyScheduleGenerator.generateNext(validFromSchedule(), CLASSIC);

        DutySchedule second = DutyScheduleGenerator.generateNext(first, CLASSIC);

        assertThat(second.month()).isEqualTo(YearMonth.of(2027, 3));
        assertThat(second.days()).hasSize(YearMonth.of(2027, 3).lengthOfMonth());
    }

    /**
     * Обов'язкове правило (вимога користувача): черговий, який чергував (D) у
     * суботу чи неділю, у понеділок отримує вихідний, навіть якщо шаблон на
     * цей понеділок каже "W". Період шаблону — 7 днів, зсув обрано так, щоб
     * 1 січня 2030 (вівторок) відповідало позиції 0 періоду — тоді 5, 6, 7
     * січня 2030 — субота, неділя, понеділок відповідно. Слот 0 (черговий 1)
     * чергує в суботу — його "W" у понеділок має бути скасоване; слот 1
     * (черговий 2) у вихідні не чергує — його "W" у понеділок лишається.
     */
    @Test
    void mondayWorkIsOverriddenToOffAfterWeekendDuty() {
        RotationTemplate template = new RotationTemplate(2, "Тест-понеділок",
                List.of("----D-W", "------W"));
        List<Engineer> engineers = List.of(
                new Engineer(1, "Черговий 1", false),
                new Engineer(2, "Черговий 2", false)
        );
        DutySchedule from = new DutySchedule(YearMonth.of(2029, 12), engineers, List.of(),
                Map.of(1, DutyMark.OFF, 2, DutyMark.OFF), Map.of(1, DutyMark.OFF, 2, DutyMark.OFF));

        DutySchedule generated = DutyScheduleGenerator.generateFromOffset(from, template, 0);

        assertThat(generated.month()).isEqualTo(YearMonth.of(2030, 1));
        var saturday = generated.days().get(4);
        assertThat(saturday.dow()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(saturday.markFor(1)).isEqualTo(DutyMark.DUTY);

        var monday = generated.days().get(6);
        assertThat(monday.dow()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(monday.markFor(1)).isEqualTo(DutyMark.OFF);
        assertThat(monday.markFor(2)).isEqualTo(DutyMark.WORK);
    }
}
