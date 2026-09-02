package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Веб-редагування графіка: позначки по днях (D/W/O/I/S/-) та ім'я/ознака
 * "лише робочі дні" наявних адміністраторів. Додавання чи видалення
 * адміністраторів із роcтеру тут не підтримується — це відносно рідка
 * операція, свідомо поза межами цієї версії.
 *
 * <p>Редагувати можна лише вже наявний місяць (створює його генератор
 * наступного місяця — окрема задача) — тут нема сенсу створювати
 * порожній графік із нуля.
 */
@Controller
public class ScheduleEditController {

    private final DutyScheduleRepository repository;

    public ScheduleEditController(DutyScheduleRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/schedule/{ym}/edit")
    public String edit(@PathVariable String ym, Model model) {
        YearMonth month = MonthPath.parse(ym);
        DutySchedule schedule = repository.find(month).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Немає графіка за " + ym + " — спершу його треба згенерувати"));

        model.addAttribute("month", month);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
        model.addAttribute("schedule", schedule);
        model.addAttribute("marks", DutyMark.values());

        return "schedule-edit";
    }

    @PostMapping("/schedule/{ym}/edit")
    public String save(@PathVariable String ym, @RequestParam Map<String, String> params, Principal principal) {
        YearMonth month = MonthPath.parse(ym);
        DutySchedule existing = repository.find(month)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));

        List<Engineer> engineers = existing.engineers().stream()
                .map(e -> new Engineer(
                        e.number(),
                        params.getOrDefault("name_" + e.number(), e.name()).strip(),
                        params.containsKey("onlyWorkdays_" + e.number())))
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

    private static Map<Integer, DutyMark> marksFor(DutyDay day, List<Engineer> engineers, Map<String, String> params) {
        Map<Integer, DutyMark> marks = new LinkedHashMap<>();
        for (Engineer e : engineers) {
            String code = params.get("mark_" + day.day() + "_" + e.number());
            marks.put(e.number(), (code == null || code.isEmpty()) ? DutyMark.OFF : DutyMark.fromChar(code.charAt(0)));
        }
        return marks;
    }
}
