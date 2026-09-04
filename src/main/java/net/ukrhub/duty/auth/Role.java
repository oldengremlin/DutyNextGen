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

    /** Українська назва ролі для показу в інтерфейсі. */
    public String displayName() {
        return displayName;
    }

    /** Ім'я для Spring Security {@code UserDetails.roles(...)} (без префікса "ROLE_"). */
    public String springRole() {
        return name();
    }
}
