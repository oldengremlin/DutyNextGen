package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.schedule.DutyScheduleGenerator;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import net.ukrhub.duty.schedule.ScheduleGenerationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Генерація графіка наступного місяця (порт {@code tds.pl}, продовжує
 * ротацію з місяця {@code ym} — {@link DutyScheduleGenerator}) і видалення
 * майбутніх місяців — обидва лише ADMIN ({@code SecurityConfig}).
 *
 * <p>Генерація прив'язана до конкретного переглянутого місяця, а не до
 * "сьогодні": можна перейти на вже згенерований жовтень і згенерувати з
 * нього листопад, і так по ланцюжку — той самий принцип, що й ручний
 * запуск {@code tds.pl -f} у застарілому проєкті, тільки без термінала.
 * Автоматичний фоновий запуск (аналог cron) — {@link
 * net.ukrhub.duty.schedule.ScheduleGenerationScheduler}.
 */
@Controller
public class ScheduleGenerationController {

    private final DutyScheduleRepository repository;

    public ScheduleGenerationController(DutyScheduleRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/schedule/{ym}/generate-next")
    public String generateNext(@PathVariable String ym, Principal principal, RedirectAttributes redirectAttributes) {
        YearMonth from = MonthPath.parse(ym);
        DutySchedule current = repository.find(from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));

        YearMonth target = from.plusMonths(1);
        if (repository.exists(target)) {
            redirectAttributes.addFlashAttribute("generationError",
                    "Графік за " + MonthPath.format(target)
                            + " вже існує — спершу видали його, якщо хочеш перегенерувати.");
            return "redirect:/schedule/" + ym;
        }

        String username = principal != null ? principal.getName() : "невідомий";
        try {
            DutySchedule generated = DutyScheduleGenerator.generateNext(current);
            repository.save(generated,
                    "Згенеровано графік " + MonthPath.format(target) + " на основі " + ym + " (" + username + ")",
                    username, username + "@duty.local");
        } catch (ScheduleGenerationException e) {
            redirectAttributes.addFlashAttribute("generationError", e.getMessage());
            return "redirect:/schedule/" + ym;
        }

        return "redirect:/schedule/" + MonthPath.format(target);
    }

    /**
     * Видаляє графік {@code ym} і каскадно всі наступні наявні місяці —
     * лише для місяців, пізніших за поточний реальний. Одна дія — один коміт.
     */
    @PostMapping("/schedule/{ym}/delete")
    public String delete(@PathVariable String ym, Principal principal) {
        YearMonth month = MonthPath.parse(ym);
        if (!month.isAfter(YearMonth.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Видаляти можна лише майбутні місяці (пізніші за поточний)");
        }
        repository.find(month)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));

        List<YearMonth> toDelete = repository.existingMonthsFrom(month);
        String monthsList = toDelete.stream().map(MonthPath::format).collect(Collectors.joining(", "));

        String username = principal != null ? principal.getName() : "невідомий";
        repository.delete(toDelete, "Видалено графік " + monthsList + " (" + username + ")",
                username, username + "@duty.local");

        return "redirect:/";
    }
}
