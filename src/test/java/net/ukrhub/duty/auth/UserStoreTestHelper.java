package net.ukrhub.duty.auth;

import java.nio.file.Path;

/**
 * {@link UserStore} — пакетно-приватний (навмисно, деталь реалізації
 * автентифікації). Цей клас — єдина точка доступу до нього з тестів інших
 * пакетів (наприклад, {@code net.ukrhub.duty.web}), яким потрібно
 * підготувати користувача для {@code TestRestTemplate.withBasicAuth}.
 */
public final class UserStoreTestHelper {

    private UserStoreTestHelper() {
    }

    public static void writeUser(Path usersFile, String username, String bcryptHash) {
        UserStore.writeUser(usersFile, username, bcryptHash);
    }
}
