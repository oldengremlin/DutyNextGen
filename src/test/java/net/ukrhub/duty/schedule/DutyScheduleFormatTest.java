package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class DutyScheduleFormatTest {

    private String loadSample() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-202609.txt")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesRealMonthFile() throws IOException {
        DutySchedule schedule = DutyScheduleFormat.parse(YearMonth.of(2026, 9), loadSample());

        assertThat(schedule.engineers()).hasSize(4);
        assertThat(schedule.engineer(1).name()).isEqualTo("Леонов О.");
        assertThat(schedule.engineer(1).onlyWorkdays()).isTrue();
        assertThat(schedule.engineer(2).name()).isEqualTo("Журавльова К.");
        assertThat(schedule.engineer(2).onlyWorkdays()).isFalse();

        assertThat(schedule.days()).hasSize(30);

        var day1 = schedule.days().get(0);
        assertThat(day1.day()).isEqualTo(1);
        assertThat(day1.dow()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(day1.markFor(1)).isEqualTo(DutyMark.WORK);
        assertThat(day1.markFor(2)).isEqualTo(DutyMark.OFF);
        assertThat(day1.markFor(3)).isEqualTo(DutyMark.DUTY);

        assertThat(schedule.lastDay0().get(3)).isEqualTo(DutyMark.DUTY);
        assertThat(schedule.lastDay1().get(3)).isEqualTo(DutyMark.DUTY);
    }

    @Test
    void roundTripsThroughSerialize() throws IOException {
        DutySchedule original = DutyScheduleFormat.parse(YearMonth.of(2026, 9), loadSample());

        String serialized = DutyScheduleFormat.serialize(original);
        DutySchedule reparsed = DutyScheduleFormat.parse(YearMonth.of(2026, 9), serialized);

        assertThat(reparsed.engineers()).isEqualTo(original.engineers());
        assertThat(reparsed.days()).isEqualTo(original.days());
        assertThat(reparsed.lastDay0()).isEqualTo(original.lastDay0());
        assertThat(reparsed.lastDay1()).isEqualTo(original.lastDay1());
    }
}
