package net.ukrhub.duty.auth;

import net.ukrhub.duty.config.DutyProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Керування обліковими записами веб-автентифікації через веб — лише для
 * {@link Role#ADMIN} (обмежено URL-матчером у {@link SecurityConfig}).
 * Первинний (перший, бутстрап) обліковий запис усе одно заводиться через
 * CLI ({@link UserAdminCli}) — до першого адміністратора цю сторінку
 * нікому й не було б видно.
 */
@Controller
public class UserAdminController {

    private final Path usersFile;
    private final PasswordEncoder passwordEncoder;

    public UserAdminController(DutyProperties properties, PasswordEncoder passwordEncoder) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
        this.passwordEncoder = passwordEncoder;
    }

    /** DTO для шаблону — {@code UserStore.StoredUser} пакетно-приватний навмисно. */
    public record UserRow(String username, Role role) {
    }

    @GetMapping("/admin/users")
    public String list(Model model) {
        Map<String, UserStore.StoredUser> users = new TreeMap<>(UserStore.readUsers(usersFile));
        List<UserRow> rows = users.entrySet().stream()
                .map(e -> new UserRow(e.getKey(), e.getValue().role()))
                .toList();
        model.addAttribute("users", rows);
        model.addAttribute("roles", Role.values());
        return "admin-users";
    }

    @PostMapping("/admin/users/create")
    public String create(@RequestParam String username, @RequestParam String password,
                          @RequestParam String confirm, @RequestParam Role role) {
        username = username.strip();
        if (username.isBlank() || !password.equals(confirm) || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ім'я не може бути порожнім, паролі мають збігатися й бути непорожніми");
        }
        if (UserStore.readUsers(usersFile).containsKey(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Користувач '" + username + "' вже існує");
        }
        UserStore.writeUser(usersFile, username, passwordEncoder.encode(password), role);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{username}/role")
    public String changeRole(@PathVariable String username, @RequestParam Role role) {
        UserStore.StoredUser existing = requireUser(username);
        UserStore.writeUser(usersFile, username, existing.passwordHash(), role);
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{username}/password")
    public String resetPassword(@PathVariable String username, @RequestParam String password,
                                 @RequestParam String confirm) {
        UserStore.StoredUser existing = requireUser(username);
        if (!password.equals(confirm) || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Паролі мають збігатися й бути непорожніми");
        }
        UserStore.writeUser(usersFile, username, passwordEncoder.encode(password), existing.role());
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{username}/delete")
    public String delete(@PathVariable String username, Principal principal) {
        requireUser(username);
        if (principal != null && principal.getName().equals(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Не можна видалити самого себе — увійди іншим адміністратором або скористайся CLI");
        }
        UserStore.deleteUser(usersFile, username);
        return "redirect:/admin/users";
    }

    private UserStore.StoredUser requireUser(String username) {
        UserStore.StoredUser user = UserStore.readUsers(usersFile).get(username);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає користувача '" + username + "'");
        }
        return user;
    }
}
