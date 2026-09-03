package net.ukrhub.duty.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Парсинг/форматування YYYYMM у шляхах — спільне для перегляду й редагування. */
final class MonthPath {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private MonthPath() {
    }

    static YearMonth parse(String ym) {
        try {
            return YearMonth.parse(ym, YM);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Невірний формат місяця: " + ym + " (очікую YYYYMM)");
        }
    }

    static String format(YearMonth month) {
        return YM.format(month);
    }
}
