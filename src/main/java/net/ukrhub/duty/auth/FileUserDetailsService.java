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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Облікові записи веб-автентифікації — окремий текстовий файл
 * {@code <config-dir>/users.txt} (рядок на користувача:
 * {@code ім'я:bcrypt-хеш:роль}), навмисно поза git-історією графіка (це не
 * дані чергувань, а секрети). За замовчуванням файлу нема — жоден вхід
 * неможливий, доки адміністратор не створить першого користувача через
 * CLI (див. README.md, розділ «Первинна ініціалізація»).
 */
@Service
public class FileUserDetailsService implements UserDetailsService {

    private final Path usersFile;

    public FileUserDetailsService(DutyProperties properties) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserStore.StoredUser stored = UserStore.readUsers(usersFile).get(username);
        if (stored == null) {
            throw new UsernameNotFoundException("User not found: " + username);
        }
        return User.withUsername(username)
                .password(stored.passwordHash())
                .roles(stored.role().springRole())
                .build();
    }
}
