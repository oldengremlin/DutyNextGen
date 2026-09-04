/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DutyScheduleFormatTest {

    private String loadSample() throws IOException {
        return loadResource("/sample-202609.txt");
    }

    private String loadResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(name)) {
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

    /**
     * Регресія на реальний production-баг: "1  Fr*" (зірочка-маркер свята
     * одразу після скорочення дня тижня) валив парсинг з NPE, бо день
     * тижня шукався точним збігом усього токена "Fr*", який ніколи не
     * дорівнював жодному з "Mo".."Su".
     */
    @Test
    void parsesHolidayMarker() throws IOException {
        DutySchedule schedule = DutyScheduleFormat.parse(YearMonth.of(2026, 5),
                loadResource("/sample-202605-holiday.txt"));

        var day1 = schedule.days().get(0);
        assertThat(day1.day()).isEqualTo(1);
        assertThat(day1.dow()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(day1.holiday()).isTrue();

        var day2 = schedule.days().get(1);
        assertThat(day2.holiday()).isFalse();

        String serialized = DutyScheduleFormat.serialize(schedule);
        assertThat(serialized).contains("1  Fr*");

        DutySchedule reparsed = DutyScheduleFormat.parse(YearMonth.of(2026, 5), serialized);
        assertThat(reparsed.days()).isEqualTo(schedule.days());
    }

    /** Файли, збережені до появи шаблонів ротації, не мають [ Tid ] — це штатно, не помилка. */
    @Test
    void realMonthFileHasNoTidByDefault() throws IOException {
        DutySchedule schedule = DutyScheduleFormat.parse(YearMonth.of(2026, 9), loadSample());

        assertThat(schedule.tid()).isNull();
        assertThat(DutyScheduleFormat.serialize(schedule)).doesNotContain("[ Tid ]");
    }

    @Test
    void tidRoundTripsThroughSerialize() {
        DutySchedule original = simpleSchedule(3, 42);

        String serialized = DutyScheduleFormat.serialize(original);
        DutySchedule reparsed = DutyScheduleFormat.parse(original.month(), serialized);

        assertThat(serialized).contains("[ Tid ]\n42\n");
        assertThat(reparsed.tid()).isEqualTo(42);
    }

    /** Узагальнення [ LastDay0 ]/[ LastDay1 ] на довільну кількість — K=3 (не лише звичний випадок двох чергових). */
    @Test
    void generalizedLastDaySectionsRoundTripForThreeSlots() {
        DutySchedule original = simpleSchedule(3, null);

        String serialized = DutyScheduleFormat.serialize(original);
        DutySchedule reparsed = DutyScheduleFormat.parse(original.month(), serialized);

        assertThat(serialized).contains("[ LastDay0 ]").contains("[ LastDay1 ]").contains("[ LastDay2 ]");
        assertThat(reparsed.lastDays()).hasSize(3).isEqualTo(original.lastDays());
    }

    private static DutySchedule simpleSchedule(int rotatingCount, Integer tid) {
        List<Engineer> engineers = List.of(
                new Engineer(1, "Черговий 1", false),
                new Engineer(2, "Черговий 2", false),
                new Engineer(3, "Черговий 3", false)
        ).subList(0, rotatingCount);
        List<DutyDay> days = List.of(new DutyDay(1, DayOfWeek.MONDAY, false, Map.of(1, DutyMark.DUTY)));
        List<Map<Integer, DutyMark>> lastDays = List.of(
                Map.of(1, DutyMark.OFF, 2, DutyMark.DUTY, 3, DutyMark.OFF),
                Map.of(1, DutyMark.OFF, 2, DutyMark.OFF, 3, DutyMark.DUTY),
                Map.of(1, DutyMark.DUTY, 2, DutyMark.OFF, 3, DutyMark.OFF)
        ).subList(0, rotatingCount);
        return new DutySchedule(YearMonth.of(2030, 6), engineers, days, lastDays, tid);
    }
}
