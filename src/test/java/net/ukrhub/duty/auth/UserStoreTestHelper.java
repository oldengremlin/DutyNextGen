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

    public static void writeUser(Path usersFile, String username, String bcryptHash, Role role) {
        UserStore.writeUser(usersFile, username, bcryptHash, role);
    }

    public static void writeUser(Path usersFile, String username, String bcryptHash, Role role, String linkedEngineer) {
        UserStore.writeUser(usersFile, username, bcryptHash, role, linkedEngineer);
    }

    public static void writeAdmin(Path usersFile, String username, String bcryptHash) {
        UserStore.writeUser(usersFile, username, bcryptHash, Role.ADMIN);
    }

    public static String readLinkedEngineer(Path usersFile, String username) {
        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get(username);
        return stored != null ? stored.linkedEngineer() : null;
    }
}
