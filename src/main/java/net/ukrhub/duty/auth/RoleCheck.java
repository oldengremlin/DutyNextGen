package net.ukrhub.duty.auth;

import org.springframework.security.core.Authentication;

/** Перевірка ролі поточного користувача — там, де URL-матчер у {@link SecurityConfig} не покриває деталі. */
public final class RoleCheck {

    private RoleCheck() {
    }

    public static boolean has(Authentication authentication, Role role) {
        if (authentication == null) {
            return false;
        }
        String authority = "ROLE_" + role.springRole();
        return authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals(authority));
    }
}
