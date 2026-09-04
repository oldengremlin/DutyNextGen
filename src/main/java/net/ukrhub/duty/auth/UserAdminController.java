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

    /**
     * Мінімальна довжина пароля. Раніше перевірялось лише "не порожній" —
     * тобто пароль з однієї літери проходив, а Basic-автентифікація не має
     * ні обмеження спроб, ні затримки: підбір такого пароля — питання
     * секунд. Вісім символів — компроміс між NIST SP 800-63B (мінімум 8)
     * і тим, що пароль тут вводить людина, а не менеджер паролів.
     */
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final Path usersFile;
    private final PasswordEncoder passwordEncoder;
    private final DutyScheduleRepository scheduleRepository;
    private final CalDavSyncService calDavSyncService;

    /**
     * Репозиторій графіка й CalDAV-сервіс потрібні не для керування
     * користувачами як таких, а для самої сторінки: список імен інженерів для
     * випадаючого списку прив'язки й кнопка «Синхронізувати зараз», яка живе
     * на тій самій сторінці адміністрування.
     */
    public UserAdminController(DutyProperties properties, PasswordEncoder passwordEncoder,
                                DutyScheduleRepository scheduleRepository, CalDavSyncService calDavSyncService) {
        this.usersFile = properties.configDirPath().resolve(UserStore.USERS_FILE_NAME);
        this.passwordEncoder = passwordEncoder;
        this.scheduleRepository = scheduleRepository;
        this.calDavSyncService = calDavSyncService;
    }

    /**
     * DTO для шаблону — {@code UserStore.StoredUser} пакетно-приватний навмисно.
     *
     * @param username       ім'я облікового запису
     * @param role           його роль
     * @param linkedEngineer П.І.Б. прив'язаного інженера, або {@code null}
     */
    public record UserRow(String username, Role role, String linkedEngineer) {
    }

    /**
     * Сторінка керування обліковими записами. {@code TreeMap} — щоб порядок
     * рядків не залежав від порядку записів у файлі й не стрибав після кожного
     * збереження.
     */
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

    /**
     * Створює обліковий запис.
     *
     * @param username       ім'я нового користувача
     * @param password       пароль (не менше {@link #MIN_PASSWORD_LENGTH} символів)
     * @param confirm        те саме ще раз — захист від опечатки
     * @param role           роль нового користувача
     * @param linkedEngineer П.І.Б. інженера для прив'язки, або порожньо
     * @throws ResponseStatusException 400 — порожнє ім'я, неприйнятний пароль
     *         чи роздільник полів у полі; 409 — такий користувач уже є
     */
    @PostMapping("/admin/users/create")
    public String create(@RequestParam String username, @RequestParam String password,
                          @RequestParam String confirm, @RequestParam Role role,
                          @RequestParam(required = false) String linkedEngineer) {
        username = username.strip();
        if (username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ім'я не може бути порожнім");
        }
        requirePasswordPair(password, confirm);
        if (UserStore.readUsers(usersFile).containsKey(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Користувач '" + username + "' вже існує");
        }
        String link = normalizeLink(linkedEngineer);
        requireStorable(username, "Ім'я користувача");
        requireStorable(link, "Ім'я прив'язаного інженера");
        UserStore.writeUser(usersFile, username, passwordEncoder.encode(password), role, link);
        return "redirect:/admin/users";
    }

    /**
     * Пара "пароль + підтвердження" з форми. Мінімальна довжина —
     * {@link #MIN_PASSWORD_LENGTH}; та сама перевірка і при створенні
     * користувача, і при скиданні пароля, щоб слабкий пароль не можна було
     * протягнути через другу форму в обхід першої.
     */
    private static void requirePasswordPair(String password, String confirm) {
        if (!password.equals(confirm)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Паролі мають збігатися");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Пароль має містити щонайменше " + MIN_PASSWORD_LENGTH + " символів");
        }
    }

    /**
     * Те саме обмеження на роздільник полів, що й у {@code UserStore}, але
     * як 400, а не 500: сюди значення приходить із форми, тож користувач
     * має побачити зрозумілу відмову, а не Whitelabel Error Page.
     */
    private static void requireStorable(String value, String fieldLabel) {
        try {
            UserStore.requireStorable(value, fieldLabel);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Прив'язує (чи відв'язує — порожнім значенням) користувача до інженера.
     * Пароль і роль лишаються як були.
     */
    @PostMapping("/admin/users/{username}/link")
    public String link(@PathVariable String username, @RequestParam(required = false) String linkedEngineer) {
        UserStore.StoredUser existing = requireUser(username);
        String link = normalizeLink(linkedEngineer);
        requireStorable(link, "Ім'я прив'язаного інженера");
        UserStore.writeUser(usersFile, username, existing.passwordHash(), existing.role(), link);
        return "redirect:/admin/users";
    }

    /** Порожній вибір у формі («— не прив'язано —») — це {@code null} у файлі, а не порожній рядок. */
    private static String normalizeLink(String linkedEngineer) {
        return (linkedEngineer == null || linkedEngineer.isBlank()) ? null : linkedEngineer.strip();
    }

    /**
     * Змінює роль. Собі — заборонено (див. коментар у тілі), останнього
     * адміністратора понизити не можна.
     */
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

    /**
     * Скидає пароль, зберігаючи роль і прив'язку. Старий пароль не питається:
     * це дія адміністратора над чужим записом, а не зміна власного.
     */
    @PostMapping("/admin/users/{username}/password")
    public String resetPassword(@PathVariable String username, @RequestParam String password,
                                 @RequestParam String confirm) {
        UserStore.StoredUser existing = requireUser(username);
        requirePasswordPair(password, confirm);
        UserStore.writeUser(usersFile, username, passwordEncoder.encode(password), existing.role());
        return "redirect:/admin/users";
    }

    /** Видаляє обліковий запис. Себе — заборонено, останнього адміністратора — теж. */
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

    /**
     * Наявний користувач за іменем.
     *
     * @throws ResponseStatusException 404, якщо такого нема (запит по URL в обхід форми)
     */
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
