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
package net.ukrhub.duty.domain;

/**
 * Позначка дня для одного адміністратора. Формат успадкований від
 * застарілого проєкту (tds.pl / index.pl / duty2ics.pl) — літери й далі
 * означають те саме.
 */
public enum DutyMark {
    DUTY('D'),       // чергування
    WORK('W'),       // звичайний робочий день
    VACATION('O'),   // відпустка
    SICK('I'),       // лікарняний
    SESSION('S'),    // сесія (студенти на заочному/вечірньому навчанні)
    OFF('-');         // вихідний / немає позначки

    private final char code;

    DutyMark(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public static DutyMark fromChar(char c) {
        for (DutyMark m : values()) {
            if (m.code == c) {
                return m;
            }
        }
        return OFF;
    }

    /** Однолітерна українська позначка для відображення у веб-таблиці (не для файлу). */
    public String displayLetter() {
        return switch (this) {
            case DUTY -> "Ч";
            case WORK -> "Р";
            case VACATION -> "В";
            case SICK -> "Л";
            case SESSION -> "С";
            case OFF -> "";
        };
    }

    /** CSS-клас для кольорового кодування позначки у веб-таблиці. */
    public String cssClass() {
        return "mark-" + name().toLowerCase();
    }

    /** Повна українська назва — для випадного списку у формі редагування. */
    public String displayName() {
        return switch (this) {
            case DUTY -> "Чергування";
            case WORK -> "Робочий день";
            case VACATION -> "Відпустка";
            case SICK -> "Лікарняний";
            case SESSION -> "Сесія";
            case OFF -> "Вихідний";
        };
    }
}
