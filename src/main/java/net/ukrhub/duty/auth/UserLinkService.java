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

import net.ukrhub.duty.config.DutyProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Переносить прив'язку "Користувача" до "Адміністратора" (інженера) при
 * перейменуванні П.І.Б. — викликається з {@code ScheduleEditController} після
 * успішного збереження графіка, якщо адміністратор змінив чиєсь ім'я.
 *
 * <p>Прив'язка зберігається за іменем (єдині наявні дані, які не треба
 * вигадувати заново), тож перейменування (напр. виправлення літери:
 * "Леонов" → "Лєонов") без цього кроку тихо розірвало б зв'язок. Історичні
 * місяці не враховуються навмисно — важливі лише поточні П.І.Б. та поточні
 * користувачі.
 */
@Service
public class UserLinkService {

    private final Path usersFile;

    /** Шлях до {@code users.txt} фіксується на старті; сам файл читається на кожну операцію. */
    public UserLinkService(DutyProperties properties) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
    }

    /**
     * П.І.Б. інженера, прив'язаного до {@code username} — {@code UserStore}
     * пакетно-приватний навмисно (див. {@code UserAdminController.UserRow}),
     * це єдиний легальний спосіб дізнатись прив'язку з інших пакетів
     * (потрібно {@code DutyExchangeController}, щоб визначити, чий графік
     * показувати в діалозі обміну чергуваннями).
     */
    public Optional<String> linkedEngineerOf(String username) {
        UserStore.StoredUser user = UserStore.readUsers(usersFile).get(username);
        return user != null ? Optional.ofNullable(user.linkedEngineer()) : Optional.empty();
    }

    /**
     * Переносить прив'язку всіх користувачів зі старого П.І.Б. на нове.
     * Збіг імен — не помилка: перейменування без реальної зміни просто нічого
     * не робить.
     */
    public void renameEngineer(String oldName, String newName) {
        if (oldName.equals(newName)) {
            return;
        }
        Map<String, UserStore.StoredUser> users = UserStore.readUsers(usersFile);
        for (var entry : users.entrySet()) {
            UserStore.StoredUser user = entry.getValue();
            if (oldName.equals(user.linkedEngineer())) {
                UserStore.writeUser(usersFile, entry.getKey(), user.passwordHash(), user.role(), newName);
            }
        }
    }
}
