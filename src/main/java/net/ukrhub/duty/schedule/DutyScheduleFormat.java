package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;

import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Читання й запис текстового формату графіка, успадкованого від
 * застарілого Perl-проєкту: секції {@code [ Names ]} / {@code [ Dates ] } /
 * {@code [ LastDay0 ] } / {@code [ LastDay1 ] }, позначки D/W/O/I/S/-, і
 * опційний маркер свята {@code *} одразу після скорочення дня тижня
 * (напр. {@code 1  Th*}) — {@link DutyDay#holiday()}.
 *
 * <p>Формат навмисно не змінюється (людинозрозумілий, добре діффиться в
 * git) — змінюється лише кодування (UTF-8 замість KOI8-U) і те, що новий
 * запис більше не містить застарілого CVS-заголовка {@code $Id$}.
 */
public final class DutyScheduleFormat {

    // Порядок відповідає Date::Calc::Day_of_Week_Abbreviation (1=Пн..7=Нд),
    // саме такі двобуквені скорочення писав tds.pl.
    private static final String[] DOW_ABBREV = {"Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"};

    private DutyScheduleFormat() {
    }

    public static DutySchedule parse(YearMonth month, String content) {
        List<Engineer> engineers = new ArrayList<>();
        List<DutyDay> days = new ArrayList<>();
        Map<Integer, DutyMark> lastDay0 = new LinkedHashMap<>();
        Map<Integer, DutyMark> lastDay1 = new LinkedHashMap<>();

        String section = "";
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            if (line.matches("^\\[\\s*\\S+\\s*\\].*")) {
                section = line.replaceAll("^\\[\\s*(\\S+)\\s*\\].*$", "$1").toLowerCase();
                continue;
            }

            switch (section) {
                case "names" -> parseNameLine(line, engineers);
                case "dates" -> parseDayLine(line, engineers, days);
                case "lastday0" -> parseTailLine(line, engineers, lastDay0);
                case "lastday1" -> parseTailLine(line, engineers, lastDay1);
                default -> {
                    // невідома/порожня секція — ігноруємо
                }
            }
        }

        return new DutySchedule(month, engineers, days, lastDay0, lastDay1);
    }

    private static void parseNameLine(String line, List<Engineer> engineers) {
        if (!Character.isDigit(line.charAt(0))) {
            return;
        }
        String[] parts = line.split(":", 3);
        int number = Integer.parseInt(parts[0].trim());
        String name = parts.length > 1 ? parts[1] : "";
        boolean onlyWorkdays = parts.length > 2 && parts[2].trim().equals("+");
        engineers.add(new Engineer(number, name, onlyWorkdays));
    }

    private static void parseDayLine(String line, List<Engineer> engineers, List<DutyDay> days) {
        if (!Character.isDigit(line.charAt(0))) {
            return;
        }
        String[] tokens = line.trim().split("\\s+");
        int day = Integer.parseInt(tokens[0]);
        String dowToken = tokens.length > 1 ? tokens[1] : "";
        // Державне свято/особливий день позначається "*" одразу після
        // скорочення дня тижня (напр. "1  Th*") — успадковано з index.pl.
        boolean holiday = dowToken.endsWith("*");
        String dowAbbrev = holiday ? dowToken.substring(0, dowToken.length() - 1) : dowToken;
        Map<Integer, DutyMark> marks = marksFor(tokens, 2, engineers);
        days.add(new DutyDay(day, dowFor(dowAbbrev), holiday, marks));
    }

    private static void parseTailLine(String line, List<Engineer> engineers, Map<Integer, DutyMark> out) {
        if (!line.startsWith("--")) {
            return;
        }
        String[] tokens = line.trim().split("\\s+");
        out.putAll(marksFor(tokens, 2, engineers));
    }

    private static Map<Integer, DutyMark> marksFor(String[] tokens, int fromIndex, List<Engineer> engineers) {
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (int i = 0; i < engineers.size(); i++) {
            int tokenIndex = fromIndex + i;
            char code = tokenIndex < tokens.length && !tokens[tokenIndex].isEmpty()
                    ? tokens[tokenIndex].charAt(0)
                    : DutyMark.OFF.code();
            marks.put(engineers.get(i).number(), DutyMark.fromChar(code));
        }
        return marks;
    }

    private static DayOfWeek dowFor(String abbrev) {
        if (abbrev != null) {
            for (int i = 0; i < DOW_ABBREV.length; i++) {
                if (DOW_ABBREV[i].equalsIgnoreCase(abbrev)) {
                    return DayOfWeek.of(i + 1);
                }
            }
        }
        return null;
    }

    public static String serialize(DutySchedule schedule) {
        StringBuilder sb = new StringBuilder();

        sb.append("[ Names ]\n");
        sb.append("# System Administrators' names:\n");
        for (Engineer e : schedule.engineers()) {
            sb.append(e.number()).append(':').append(e.name()).append(':')
                    .append(e.onlyWorkdays() ? "+" : "").append('\n');
        }

        sb.append("[ Dates ]\n");
        sb.append("# day");
        for (Engineer e : schedule.engineers()) {
            sb.append("\tAdm_").append(e.number());
        }
        sb.append('\n');
        for (DutyDay day : schedule.days()) {
            String dowField = abbrevFor(day.dow()) + (day.holiday() ? "*" : "");
            sb.append(String.format("%-2d %s", day.day(), dowField));
            for (Engineer e : schedule.engineers()) {
                sb.append('\t').append(day.markFor(e.number()).code());
            }
            sb.append('\n');
        }

        appendTailSection(sb, "LastDay0", schedule.engineers(), schedule.lastDay0());
        appendTailSection(sb, "LastDay1", schedule.engineers(), schedule.lastDay1());

        return sb.toString();
    }

    private static void appendTailSection(StringBuilder sb, String title, List<Engineer> engineers,
                                           Map<Integer, DutyMark> marks) {
        sb.append("[ ").append(title).append(" ]\n");
        sb.append("-- --");
        for (Engineer e : engineers) {
            DutyMark mark = marks.getOrDefault(e.number(), DutyMark.OFF);
            sb.append('\t').append(mark.code());
        }
        sb.append('\n');
    }

    private static String abbrevFor(DayOfWeek dow) {
        return dow == null ? "??" : DOW_ABBREV[dow.getValue() - 1];
    }
}
