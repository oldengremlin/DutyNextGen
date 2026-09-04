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

import org.springframework.security.core.Authentication;

/** Перевірка ролі поточного користувача — там, де URL-матчер у {@link SecurityConfig} не покриває деталі. */
public final class RoleCheck {

    /** Лише статичні методи. */
    private RoleCheck() {
    }

    /**
     * Чи має поточний користувач саме цю роль. Ролі не ієрархічні: адміністратор
     * не «має» ролі редактора, тож перевірки на кілька ролей робляться явним
     * або ({@code ScheduleController.canEdit}).
     *
     * @param authentication контекст запиту; {@code null} (неавтентифікований) — завжди {@code false}
     */
    public static boolean has(Authentication authentication, Role role) {
        if (authentication == null) {
            return false;
        }
        String authority = "ROLE_" + role.springRole();
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }
}
