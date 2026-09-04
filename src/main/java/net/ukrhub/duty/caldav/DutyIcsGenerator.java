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
 * по одній на кожну активну позначку (D/W/O/I/S) кожного інженера, за
 * весь місяць (без фільтра "від сьогодні й далі", на відміну від
 * оригіналу). UID детермінований (дата + номер інженера), тож повторна
 * генерація на той самий день дає той самий UID — основа ідемпотентності
 * {@link CalDavSyncService}: те, що не змінилось, дає той самий хеш
 * вмісту й нікуди не відправляється, а не публікується завжди, лише щоб
 * потім бути відфільтрованим.
 *
 * <p>Чому без фільтра "від сьогодні": позначки іноді проставляють
 * заднім числом (напр. лікарняний оформили постфактум) — і такий
 * відредагований минулий день має так само дійти до CalDAV, як і
 * майбутній. {@link CalDavSyncService} сам обмежує, які МІСЯЦІ синхронізує
 * (попередній/поточний/наступний) — цього досить, щоб не смикати
 * сервер даремно старою історією, і не потрібен додатковий фільтр за
 * ДНЯМИ всередині вже вибраних місяців.
 *
 * <p>Формат UID ({@code duty-YYYYMMDD-NUM@duty.ukrhub.net}) і вміст подій
 * (час чергування 8:00–20:00, робочого дня 9:00–17:00/17:30 з коротшою
 * п'ятницею) свідомо зберігають той самий вигляд, що й у застарілого
 * скрипту, — щоб продовжити, а не задублювати, уже опубліковані в Baikal
 * події. Позначка "S" (сесія) — розширення nextgen, якого не було в
 * оригіналі; трактуємо як подію на весь день, за тим самим принципом,
 * що й відпустку/лікарняний.
 *
 * <p>Кожна подія отримує {@code CATEGORIES} — той самий текст, що й у
 * {@code SUMMARY} ({@link DutyMark#displayName()}: "Чергування",
 * "Робочий день" тощо). Ні застарілий {@code duty2ics.pl}, ні перша
 * версія цього порту категорій не проставляли — RFC 5545 не задає
 * фіксованого переліку значень, це довільний текст, тож обрано саме
 * ці слова свідомо: в реальному Baikal-календарі вже існує вручну
 * створена категорія "Відпустка" (для того самого сенсу) — збіг
 * тексту дає ці події одразу побачити разом і однаково розфарбованими
 * в клієнті (Thunderbird тощо), не заводячи паралельний, схожий, але
 * інший ярлик.
 */
public final class DutyIcsGenerator {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Лише статичні методи. */
    private DutyIcsGenerator() {
    }

    /**
     * Події за весь місяць — по одній на кожну активну позначку кожного
     * інженера. {@link DutyMark#OFF} не публікується: «нічого» не подія.
     */
    public static List<IcsEvent> generate(DutySchedule schedule) {
        List<IcsEvent> events = new ArrayList<>();
        for (DutyDay day : schedule.days()) {
            LocalDate date = schedule.month().atDay(day.day());
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

    /**
     * Одна подія: чергування 8:00–20:00, робочий день 9:00–17:30 (у п'ятницю
     * до 17:00), відпустка/лікарняний/сесія — на весь день. Години
     * успадковані від {@code duty2ics.pl} — саме такі події вже лежать у
     * Baikal, і міняти їх означало б задублювати опубліковане.
     */
    private static IcsEvent eventFor(LocalDate date, Engineer engineer, DutyMark mark) {
        String uid = "duty-" + YMD.format(date) + "-" + engineer.number() + "@duty.ukrhub.net";
        String dtstamp = YMD.format(date) + "T000000Z";
        String category = mark.displayName();
        String summary = category + ", " + engineer.name();

        String body = switch (mark) {
            case DUTY -> timedEvent(uid, dtstamp, date, 8, 0, 20, 0, summary, category);
            case WORK -> {
                boolean friday = date.getDayOfWeek() == DayOfWeek.FRIDAY;
                yield timedEvent(uid, dtstamp, date, 9, 0, 17, friday ? 0 : 30, summary, category);
            }
            case VACATION, SICK, SESSION -> allDayEvent(uid, dtstamp, date, summary, category);
            case OFF -> throw new IllegalStateException("OFF не публікується — фільтрується в generate()");
        };

        return new IcsEvent(uid, date, body);
    }

    /** Подія з конкретним часом початку й кінця (чергування, робочий день). */
    private static String timedEvent(String uid, String dtstamp, LocalDate date, int startHour, int startMin,
                                      int endHour, int endMin, String summary, String category) {
        String dtstart = YMD.format(date) + "T" + hhmm(startHour, startMin) + "00";
        String dtend = YMD.format(date) + "T" + hhmm(endHour, endMin) + "00";
        return vcalendar(uid, dtstamp, "DTSTART:" + dtstart, "DTEND:" + dtend, summary, category);
    }

    /**
     * Подія на весь день. {@code DTEND} — наступна доба: за RFC 5545 верхня
     * межа не включна, інакше клієнт показав би подію на день коротшою.
     */
    private static String allDayEvent(String uid, String dtstamp, LocalDate date, String summary, String category) {
        String dtstart = YMD.format(date);
        String dtend = YMD.format(date.plusDays(1));
        return vcalendar(uid, dtstamp, "DTSTART;VALUE=DATE:" + dtstart, "DTEND;VALUE=DATE:" + dtend,
                summary, category);
    }

    /**
     * Обгортка {@code VCALENDAR}/{@code VEVENT} довкола вже готових рядків дат.
     * CRLF, а не {@code \n} — цього прямо вимагає RFC 5545.
     */
    private static String vcalendar(String uid, String dtstamp, String dtstartLine, String dtendLine,
                                     String summary, String category) {
        return "BEGIN:VCALENDAR\r\n"
                + "VERSION:2.0\r\n"
                + "PRODID:-//UKRCOM//duty2ics//UK\r\n"
                + "BEGIN:VEVENT\r\n"
                + "UID:" + uid + "\r\n"
                + "DTSTAMP:" + dtstamp + "\r\n"
                + dtstartLine + "\r\n"
                + dtendLine + "\r\n"
                + "SUMMARY:" + summary + "\r\n"
                + "CATEGORIES:" + category + "\r\n"
                + "END:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    /** Час у форматі {@code HHMM} для рядків {@code DTSTART}/{@code DTEND}. */
    private static String hhmm(int hour, int minute) {
        return "%02d%02d".formatted(hour, minute);
    }
}
