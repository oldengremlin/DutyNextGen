package net.ukrhub.duty.auth;

import net.ukrhub.duty.caldav.CalDavSyncService;
import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
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
import java.time.YearMonth;
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
    private final DutyScheduleRepository scheduleRepository;
    private final CalDavSyncService calDavSyncService;

    public UserAdminController(DutyProperties properties, PasswordEncoder passwordEncoder,
                                DutyScheduleRepository scheduleRepository, CalDavSyncService calDavSyncService) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
        this.passwordEncoder = passwordEncoder;
        this.scheduleRepository = scheduleRepository;
        this.calDavSyncService = calDavSyncService;
    }

    /** DTO для шаблону — {@code UserStore.StoredUser} пакетно-приватний навмисно. */
    public record UserRow(String username, Role role, String linkedEngineer) {
    }

    @GetMapping("/admin/users")
    public String list(Model model) {
        Map<String, UserStore.StoredUser> users = new TreeMap<>(UserStore.readUsers(usersFile));
        List<UserRow> rows = users.entrySet().stream()
                .map(e -> new UserRow(e.getKey(), e.getValue().role(), e.getValue().linkedEngineer()))
                .toList();
        model.addAttribute("users", rows);
        model.addAttribute("roles", Role.values());
        model.addAttribute("engineerNames", currentEngineerNames());
        model.addAttribute("caldavConfigured", calDavSyncService.configured());
        return "admin-users";
    }

    /** Імена інженерів поточного місяця — джерело для випадаючого списку прив'язки. */
    private List<String> currentEngineerNames() {
        return scheduleRepository.find(YearMonth.now())
                .map(s -> s.engineers().stream().map(Engineer::name).sorted().toList())
                .orElse(List.of());
    }

    @PostMapping("/admin/users/create")
    public String create(@RequestParam String username, @RequestParam String password,
                          @RequestParam String confirm, @RequestParam Role role,
                          @RequestParam(required = false) String linkedEngineer) {
        username = username.strip();
        if (username.isBlank() || !password.equals(confirm) || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ім'я не може бути порожнім, паролі мають збігатися й бути непорожніми");
        }
        if (UserStore.readUsers(usersFile).containsKey(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Користувач '" + username + "' вже існує");
        }
        UserStore.writeUser(usersFile, username, passwordEncoder.encode(password), role, normalizeLink(linkedEngineer));
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{username}/link")
    public String link(@PathVariable String username, @RequestParam(required = false) String linkedEngineer) {
        UserStore.StoredUser existing = requireUser(username);
        UserStore.writeUser(usersFile, username, existing.passwordHash(), existing.role(), normalizeLink(linkedEngineer));
        return "redirect:/admin/users";
    }

    private static String normalizeLink(String linkedEngineer) {
        return (linkedEngineer == null || linkedEngineer.isBlank()) ? null : linkedEngineer.strip();
    }

    @PostMapping("/admin/users/{username}/role")
    public String changeRole(@PathVariable String username, @RequestParam Role role, Principal principal) {
        UserStore.StoredUser existing = requireUser(username);
        // Безумовно, як і самовидалення нижче — інакше адміністратор,
        // маючи колегу-адміна, може сам собі за секунду прибрати права
        // й лишитися звичайним користувачем: система в цілому лишається
        // в безпеці (інший адмін є), але саме ця сесія вже ні на що не
        // впливає. requireNotLastAdmin() нижче — про запас на випадок,
        // якщо колись цю самозаборону послаблять.
        if (principal != null && principal.getName().equals(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Не можна змінити роль самому собі — попроси іншого адміністратора або скористайся CLI");
        }
        if (existing.role() == Role.ADMIN && role != Role.ADMIN) {
            requireNotLastAdmin();
        }
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
        UserStore.StoredUser existing = requireUser(username);
        if (principal != null && principal.getName().equals(username)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Не можна видалити самого себе — увійди іншим адміністратором або скористайся CLI");
        }
        // Поки діє самозаборона вище, цю гілку через API не досягти
        // (видаляти може лише інший адміністратор, який сам лишається) —
        // явна перевірка тут навмисна, як захист про запас, а не заміна.
        if (existing.role() == Role.ADMIN) {
            requireNotLastAdmin();
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

    /** У системі завжди має лишатися хоча б один адміністратор. */
    private void requireNotLastAdmin() {
        long adminCount = UserStore.readUsers(usersFile).values().stream()
                .filter(u -> u.role() == Role.ADMIN)
                .count();
        if (adminCount <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "У системі має лишатися хоча б один адміністратор");
        }
    }
}
