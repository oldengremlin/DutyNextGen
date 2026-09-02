package net.ukrhub.duty.web;

import net.ukrhub.duty.auth.Role;
import net.ukrhub.duty.auth.RoleCheck;
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

    public ScheduleEditController(DutyScheduleRepository repository) {
        this.repository = repository;
    }

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

    @PostMapping("/schedule/{ym}/edit")
    public String save(@PathVariable String ym, @RequestParam Map<String, String> params, Principal principal,
                        Authentication authentication) {
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

        List<DutyDay> days = existing.days().stream()
                .map(day -> new DutyDay(day.day(), day.dow(), day.holiday(), marksFor(day, engineers, params)))
                .toList();

        DutySchedule updated = new DutySchedule(month, engineers, days, existing.lastDay0(), existing.lastDay1());

        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(updated, "Редагування графіка " + ym + " через веб (" + username + ")",
                username, username + "@duty.local");

        return "redirect:/schedule/" + ym;
    }

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

        Map<Integer, DutyMark> lastDay0 = new LinkedHashMap<>(existing.lastDay0());
        lastDay0.put(nextNumber, DutyMark.OFF);
        Map<Integer, DutyMark> lastDay1 = new LinkedHashMap<>(existing.lastDay1());
        lastDay1.put(nextNumber, DutyMark.OFF);

        DutySchedule updated = new DutySchedule(month, engineers, days, lastDay0, lastDay1);

        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(updated, "Додано адміністратора №" + nextNumber + " (" + engineerName + ") до графіка " + ym
                        + " (" + username + ")",
                username, username + "@duty.local");

        return "redirect:/schedule/" + ym + "/edit";
    }

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

        Map<Integer, DutyMark> lastDay0 = new LinkedHashMap<>(existing.lastDay0());
        lastDay0.remove(number);
        Map<Integer, DutyMark> lastDay1 = new LinkedHashMap<>(existing.lastDay1());
        lastDay1.remove(number);

        DutySchedule updated = new DutySchedule(month, engineers, days, lastDay0, lastDay1);

        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(updated, "Видалено адміністратора №" + number + " (" + removed.name() + ") з графіка " + ym
                        + " (" + username + ")",
                username, username + "@duty.local");

        return "redirect:/schedule/" + ym + "/edit";
    }

    private static Map<Integer, DutyMark> marksFor(DutyDay day, List<Engineer> engineers, Map<String, String> params) {
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            String code = params.get("mark_" + day.day() + "_" + e.number());
            marks.put(e.number(), (code == null || code.isEmpty()) ? DutyMark.OFF : DutyMark.fromChar(code.charAt(0)));
        }
        return marks;
    }
}
