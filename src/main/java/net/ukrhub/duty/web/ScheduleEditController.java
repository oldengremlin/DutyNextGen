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
package net.ukrhub.duty.web;

import net.ukrhub.duty.auth.Role;
import net.ukrhub.duty.auth.RoleCheck;
import net.ukrhub.duty.auth.UserLinkService;
import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Веб-редагування графіка: позначки по днях (D/W/O/I/S/-), ім'я/ознака
 * "лише робочі дні" наявних адміністраторів, додавання й видалення
 * адміністраторів з ростеру.
 *
 * <p>Редагувати можна лише вже наявний місяць (створює його генератор
 * наступного місяця — окрема задача) — тут нема сенсу створювати
 * порожній графік із нуля.
 *
 * <p>Додавання/видалення адміністратора змінює лише ростер конкретного
 * місяця (і, за замовчуванням, усі дні того самого місяця — новий
 * адміністратор отримує "вихідний" на кожен день, доки хтось не
 * проставить реальні позначки). Ротаційний шаблон генератора наступного
 * місяця (задача tds.pl-порту) під іншу кількість людей поки не
 * підлаштовується — свідомо залишено на потім.
 */
@Controller
public class ScheduleEditController {

    private final DutyScheduleRepository repository;
    private final UserLinkService userLinkService;

    /**
     * {@link UserLinkService} — щоб перейменування П.І.Б. не розривало
     * прив'язку користувача до цього інженера.
     */
    public ScheduleEditController(DutyScheduleRepository repository, UserLinkService userLinkService) {
        this.repository = repository;
        this.userLinkService = userLinkService;
    }

    /**
     * Форма редагування наявного місяця.
     *
     * @throws ResponseStatusException 404, якщо місяць ще не згенеровано —
     *         створювати порожній графік з нуля тут свідомо нема чим
     */
    @GetMapping("/schedule/{ym}/edit")
    public String edit(@PathVariable String ym, Model model, Authentication authentication) {
        YearMonth month = MonthPath.parse(ym);
        DutySchedule schedule = repository.find(month).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Немає графіка за " + ym + " — спершу його треба згенерувати"));

        model.addAttribute("month", month);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
        model.addAttribute("schedule", schedule);
        model.addAttribute("marks", DutyMark.values());
        model.addAttribute("isAdmin", RoleCheck.has(authentication, Role.ADMIN));

        return "schedule-edit";
    }

    /**
     * Зберігає всю форму одним комітом: позначки по днях, свята, а для
     * адміністратора — ще й П.І.Б. та ознаку «лише робочі дні».
     *
     * @param params усі поля форми — їх кількість залежить від кількості днів і
     *        адміністраторів у місяці, тож окремими параметрами їх не описати
     * @return ту саму форму з переліком помилок, якщо валідація не пройшла;
     *         інакше редирект на перегляд місяця
     */
    @PostMapping("/schedule/{ym}/edit")
    public String save(@PathVariable String ym, @RequestParam Map<String, String> params, Principal principal,
                        Authentication authentication, Model model) {
        YearMonth month = MonthPath.parse(ym);
        DutySchedule existing = repository.find(month)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));

        // П.І.Б. і тип ("лише робочі дні") міняє лише ADMIN — форма для
        // EDITOR ці поля не показує взагалі, але це лише UX-зручність:
        // без цієї перевірки сервер прийняв би ті самі параметри й від
        // прямого POST-запиту в обхід форми.
        boolean isAdmin = RoleCheck.has(authentication, Role.ADMIN);
        List<Engineer> engineers = existing.engineers().stream()
                .map(e -> isAdmin
                        ? new Engineer(
                                e.number(),
                                params.getOrDefault("name_" + e.number(), e.name()).strip(),
                                params.containsKey("onlyWorkdays_" + e.number()))
                        : e)
                .toList();

        // Свято можна проставити на будь-який день (не лише вихідний) —
        // позначку редагує будь-хто, кому доступна ця форма, як і позначки
        // по днях (не обмежено ADMIN, на відміну від П.І.Б./типу).
        List<DutyDay> days = existing.days().stream()
                .map(day -> new DutyDay(day.day(), day.dow(), params.containsKey("holiday_" + day.day()),
                        marksFor(day, engineers, params)))
                .toList();

        List<String> errors = validateNoWorkOnWeekendsAndHolidays(days, engineers);
        if (!errors.isEmpty()) {
            model.addAttribute("month", month);
            model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
            model.addAttribute("schedule", new DutySchedule(month, engineers, days, existing.lastDays(), existing.tid()));
            model.addAttribute("marks", DutyMark.values());
            model.addAttribute("isAdmin", isAdmin);
            model.addAttribute("errors", errors);
            return "schedule-edit";
        }

        DutySchedule updated = new DutySchedule(month, engineers, days, existing.lastDays(), existing.tid());

        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(updated, "Редагування графіка " + ym + " через веб (" + username + ")",
                username, username + "@duty.local");

        // Перейменування П.І.Б. (лише ADMIN міг його змінити) переносимо й на
        // прив'язку "Користувача" до цього адміністратора — інакше вона тихо
        // розірветься при виправленні навіть однієї літери в імені.
        if (isAdmin) {
            for (Engineer before : existing.engineers()) {
                String afterName = updated.engineer(before.number()).name();
                if (!before.name().equals(afterName)) {
                    userLinkService.renameEngineer(before.name(), afterName);
                }
            }
        }

        return "redirect:/schedule/" + ym;
    }

    /**
     * У вихідні й свята не може бути "робочого дня" (W) — лише чергування
     * та інші позначки (відпустка/лікарняний/сесія/вихідний). Так само
     * поводився й генератор наступного місяця в застарілому проєкті
     * (tds.pl примусово перетворював W на "-" для суботи/неділі).
     */
    private static List<String> validateNoWorkOnWeekendsAndHolidays(List<DutyDay> days, List<Engineer> engineers) {
        List<String> errors = new ArrayList<>();
        for (DutyDay day : days) {
            if (!day.isWeekend() && !day.holiday()) {
                continue;
            }
            for (Engineer e : engineers) {
                if (day.markFor(e.number()) == DutyMark.WORK) {
                    errors.add("%d число (%s): у %s не може бути позначки «Робочий день»".formatted(
                            day.day(), day.holiday() ? "свято" : "вихідний", e.name()));
                }
            }
        }
        return errors;
    }

    /**
     * Додає адміністратора в ростер цього місяця — з «вихідним» на кожен день,
     * доки хтось не проставить реальні позначки. Номер — наступний за
     * максимальним; звільнені номери не перевикористовуються, щоб не
     * плутати історію.
     */
    @PostMapping("/schedule/{ym}/edit/add-engineer")
    public String addEngineer(@PathVariable String ym, @RequestParam String name,
                               @RequestParam(defaultValue = "false") boolean onlyWorkdays, Principal principal) {
        YearMonth month = MonthPath.parse(ym);
        DutySchedule existing = repository.find(month)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));

        int nextNumber = existing.engineers().stream().mapToInt(Engineer::number).max().orElse(0) + 1;
        String engineerName = name.isBlank() ? "Новий адміністратор" : name.strip();

        List<Engineer> engineers = new ArrayList<>(existing.engineers());
        engineers.add(new Engineer(nextNumber, engineerName, onlyWorkdays));

        List<DutyDay> days = existing.days().stream()
                .map(day -> {
                    Map<Integer, DutyMark> marks = new LinkedHashMap<>(day.marks());
                    marks.put(nextNumber, DutyMark.OFF);
                    return new DutyDay(day.day(), day.dow(), day.holiday(), marks);
                })
                .toList();

        List<Map<Integer, DutyMark>> lastDays = existing.lastDays().stream()
                .map(m -> {
                    Map<Integer, DutyMark> copy = new LinkedHashMap<>(m);
                    copy.put(nextNumber, DutyMark.OFF);
                    return copy;
                })
                .toList();

        DutySchedule updated = new DutySchedule(month, engineers, days, lastDays, existing.tid());

        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(updated, "Додано адміністратора №" + nextNumber + " (" + engineerName + ") до графіка " + ym
                        + " (" + username + ")",
                username, username + "@duty.local");

        return "redirect:/schedule/" + ym + "/edit";
    }

    /**
     * Прибирає адміністратора з ростеру цього місяця разом з усіма його
     * позначками. Інші місяці не зачіпаються.
     *
     * @throws ResponseStatusException 404, якщо такого номера в місяці нема
     */
    @PostMapping("/schedule/{ym}/edit/remove-engineer")
    public String removeEngineer(@PathVariable String ym, @RequestParam int number, Principal principal) {
        YearMonth month = MonthPath.parse(ym);
        DutySchedule existing = repository.find(month)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));

        Engineer removed = existing.engineers().stream()
                .filter(e -> e.number() == number)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає адміністратора №" + number));

        List<Engineer> engineers = existing.engineers().stream()
                .filter(e -> e.number() != number)
                .toList();

        List<DutyDay> days = existing.days().stream()
                .map(day -> {
                    Map<Integer, DutyMark> marks = new LinkedHashMap<>(day.marks());
                    marks.remove(number);
                    return new DutyDay(day.day(), day.dow(), day.holiday(), marks);
                })
                .toList();

        List<Map<Integer, DutyMark>> lastDays = existing.lastDays().stream()
                .map(m -> {
                    Map<Integer, DutyMark> copy = new LinkedHashMap<>(m);
                    copy.remove(number);
                    return copy;
                })
                .toList();

        DutySchedule updated = new DutySchedule(month, engineers, days, lastDays, existing.tid());

        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(updated, "Видалено адміністратора №" + number + " (" + removed.name() + ") з графіка " + ym
                        + " (" + username + ")",
                username, username + "@duty.local");

        return "redirect:/schedule/" + ym + "/edit";
    }

    /**
     * Позначки одного дня з полів форми {@code mark_<день>_<номер>}; відсутнє
     * поле — {@link DutyMark#OFF} (так браузер поводиться з невибраним значенням).
     */
    private static Map<Integer, DutyMark> marksFor(DutyDay day, List<Engineer> engineers, Map<String, String> params) {
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            String code = params.get("mark_" + day.day() + "_" + e.number());
            marks.put(e.number(), (code == null || code.isEmpty()) ? DutyMark.OFF : DutyMark.fromChar(code.charAt(0)));
        }
        return marks;
    }
}
