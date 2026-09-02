package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Веб-перегляд графіка на місяць — порт {@code index.pl} застарілого
 * проєкту. Редагування — окремий контролер (наступна задача).
 */
@Controller
public class ScheduleController {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final DutyScheduleRepository repository;

    public ScheduleController(DutyScheduleRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/schedule/" + YM.format(YearMonth.now());
    }

    @GetMapping("/schedule/{ym}")
    public String schedule(@PathVariable String ym, Model model) {
        YearMonth month = parseYearMonth(ym);

        model.addAttribute("month", month);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
        model.addAttribute("prevYm", YM.format(month.minusMonths(1)));
        model.addAttribute("nextYm", YM.format(month.plusMonths(1)));

        boolean isCurrentMonth = YearMonth.now().equals(month);
        model.addAttribute("todayDay", isCurrentMonth ? LocalDate.now().getDayOfMonth() : -1);

        DutySchedule schedule = repository.find(month).orElse(null);
        model.addAttribute("schedule", schedule);

        return "schedule";
    }

    private static YearMonth parseYearMonth(String ym) {
        try {
            return YearMonth.parse(ym, YM);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Невірний формат місяця: " + ym + " (очікую YYYYMM)");
        }
    }
}
