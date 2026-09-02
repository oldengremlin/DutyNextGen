package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Веб-перегляд графіка на місяць — порт {@code index.pl} застарілого
 * проєкту. Редагування — {@link ScheduleEditController}.
 */
@Controller
public class ScheduleController {

    private final DutyScheduleRepository repository;

    public ScheduleController(DutyScheduleRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/schedule/" + MonthPath.format(YearMonth.now());
    }

    @GetMapping("/schedule/{ym}")
    public String schedule(@PathVariable String ym, Model model) {
        YearMonth month = MonthPath.parse(ym);

        model.addAttribute("month", month);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
        model.addAttribute("prevYm", MonthPath.format(month.minusMonths(1)));
        model.addAttribute("nextYm", MonthPath.format(month.plusMonths(1)));

        boolean isCurrentMonth = YearMonth.now().equals(month);
        model.addAttribute("todayDay", isCurrentMonth ? LocalDate.now().getDayOfMonth() : -1);

        DutySchedule schedule = repository.find(month).orElse(null);
        model.addAttribute("schedule", schedule);

        return "schedule";
    }
}
