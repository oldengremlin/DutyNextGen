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
package net.ukrhub.duty.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Парсинг/форматування YYYYMM у шляхах — спільне для перегляду й редагування. */
final class MonthPath {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    /** Лише статичні методи. */
    private MonthPath() {
    }

    /**
     * Місяць зі шляху.
     *
     * @throws ResponseStatusException 404 (а не 400) для нерозбірного значення:
     *         з погляду користувача такої сторінки просто нема
     */
    static YearMonth parse(String ym) {
        try {
            return YearMonth.parse(ym, YM);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Невірний формат місяця: " + ym + " (очікую YYYYMM)");
        }
    }

    /** Місяць у вигляді {@code YYYYMM} — і для URL, і для імені файлу. */
    static String format(YearMonth month) {
        return YM.format(month);
    }
}
