package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DutyScheduleGeneratorTest {

    /**
     * Позиція i=44 (найперша, яку перевіряє пошук фази — {@code TEMPLATE[43]}
     * і {@code TEMPLATE[44]=TEMPLATE[0]} завдяки подвоєнню шаблону) —
     * детермінований, не залежний від внутрішньої структури базового
     * шаблону якір для тестів: {@code TEMPLATE[43]="-:D"}, {@code
     * TEMPLATE[0]="D:-"}.
     */
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

        assertThatThrownBy(() -> DutyScheduleGenerator.generateNext(from))
                .isInstanceOf(ScheduleGenerationException.class)
                .hasMessageContaining("рівно двох чергових");
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

        assertThatThrownBy(() -> DutyScheduleGenerator.generateNext(from))
                .isInstanceOf(ScheduleGenerationException.class)
                .hasMessageContaining("фазу ротаційного шаблону");
    }

    @Test
    void generatesNextMonthAndNeverPutsWorkOnWeekendForOnlyWorkdaysEngineer() {
        DutySchedule generated = DutyScheduleGenerator.generateNext(validFromSchedule());

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

    @Test
    void generatedScheduleCanBeChainedIntoFollowingMonth() {
        DutySchedule first = DutyScheduleGenerator.generateNext(validFromSchedule());

        DutySchedule second = DutyScheduleGenerator.generateNext(first);

        assertThat(second.month()).isEqualTo(YearMonth.of(2027, 3));
        assertThat(second.days()).hasSize(YearMonth.of(2027, 3).lengthOfMonth());
    }
}
