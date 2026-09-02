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
            case OFF -> "";
        };
    }

    /** CSS-клас для кольорового кодування позначки у веб-таблиці. */
    public String cssClass() {
        return "mark-" + name().toLowerCase();
    }
}
