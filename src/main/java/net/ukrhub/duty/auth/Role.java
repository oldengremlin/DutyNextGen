package net.ukrhub.duty.auth;

/**
 * Роль користувача веб-автентифікації.
 *
 * <ul>
 *   <li>{@link #VIEWER} — лише перегляд графіка.</li>
 *   <li>{@link #EDITOR} — перегляд + позначки по днях, БЕЗ зміни П.І.Б.,
 *       ознаки "лише робочі дні" й без керування ростером
 *       (додати/видалити адміністратора) — це перевіряється окремо в
 *       {@code ScheduleEditController}, оскільки форма збереження
 *       позначок і зміни П.І.Б. — той самий POST-запит.</li>
 *   <li>{@link #ADMIN} — усе, плюс керування ростером і сторінка
 *       адміністрування користувачів ({@code /admin/users}).</li>
 * </ul>
 */
public enum Role {
    VIEWER("Користувач"),
    EDITOR("Редактор"),
    ADMIN("Адміністратор");

    private final String displayName;

    Role(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Ім'я для Spring Security {@code UserDetails.roles(...)} (без префікса "ROLE_"). */
    public String springRole() {
        return name();
    }
}
