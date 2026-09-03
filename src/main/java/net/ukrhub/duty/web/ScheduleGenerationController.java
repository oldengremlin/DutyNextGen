package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.schedule.DutyScheduleGenerator;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import net.ukrhub.duty.schedule.ScheduleGenerationException;
import net.ukrhub.duty.template.RotationTemplateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Генерація графіка наступного місяця ({@link DutyScheduleGenerator},
 * продовжує ротацію з місяця {@code ym}) і видалення майбутніх місяців —
 * обидва лише ADMIN ({@code SecurityConfig}).
 *
 * <p>Дерево рішень для «Згенерувати» — {@code nextgen/docs/rotation-templates.md}:
 * визначаємо K (кількість ротаційних адміністраторів місяця {@code ym}),
 * шукаємо серед {@link RotationTemplateRepository} шаблони під це K.
 * Нуль — помилка. Один — без діалогу вибору ШАБЛОНУ (він і так
 * однозначний), просто продовжуємо фазу — але якщо фазу продовжити не
 * вдалось (типовий випадок: K щойно змінилося, і збережений хвіст
 * {@code [ LastDayN ] } ще старого розміру), однозначний шаблон не
 * рятує від необхідності явно обрати зсув — ведемо одразу на крок
 * вибору дня періоду для нього (а не показуємо голу помилку). Два і
 * більше — завжди питаємо (незалежно від того, який шаблон використано
 * минулого разу): спершу який шаблон (`/templates`, з наочним прев'ю),
 * потім з якого дня періоду почати (`/offset`, з візуалізацією реального
 * хвоста поточного місяця + кожного можливого продовження) — і лише
 * тоді генеруємо, без пошуку фази, з точного зсуву.
 *
 * <p>Генерація прив'язана до конкретного переглянутого місяця, а не до
 * "сьогодні": можна перейти на вже згенерований жовтень і згенерувати з
 * нього листопад, і так по ланцюжку. Автоматичний фоновий запуск (аналог
 * cron) — {@link net.ukrhub.duty.schedule.ScheduleGenerationScheduler} —
 * ніколи не питає, завжди продовжує той самий шаблон, що й минулого разу
 * ({@code DutySchedule#tid()}).
 */
@Controller
public class ScheduleGenerationController {

    /** Скільки днів хвоста поточного місяця й початку прев'ю показувати при виборі зсуву. */
    private static final int PREVIEW_DAYS = 8;

    private final DutyScheduleRepository repository;
    private final RotationTemplateRepository templateRepository;

    public ScheduleGenerationController(DutyScheduleRepository repository, RotationTemplateRepository templateRepository) {
        this.repository = repository;
        this.templateRepository = templateRepository;
    }

    @PostMapping("/schedule/{ym}/generate-next")
    public String generateNext(@PathVariable String ym, Principal principal, RedirectAttributes redirectAttributes) {
        YearMonth from = MonthPath.parse(ym);
        DutySchedule current = requireCurrent(from, ym);
        if (targetAlreadyExists(from, ym, redirectAttributes)) {
            return "redirect:/schedule/" + ym;
        }

        int rotatingCount = rotatingCount(current);
        List<RotationTemplate> candidates = templatesForSlots(rotatingCount);

        if (candidates.isEmpty()) {
            redirectAttributes.addFlashAttribute("generationError",
                    "Немає жодного шаблону ротації на " + rotatingCount + " чергових — створи його на "
                            + "/admin/templates, або згенеруй наступний місяць вручну через редагування.");
            return "redirect:/schedule/" + ym;
        }
        if (candidates.size() > 1) {
            return "redirect:/schedule/" + ym + "/generate-next/templates";
        }

        RotationTemplate template = candidates.get(0);
        DutySchedule generated;
        try {
            generated = DutyScheduleGenerator.generateNext(current, template);
        } catch (ScheduleGenerationException e) {
            // Шаблон однозначний, але фазу продовжити не вдалось (типово — K
            // щойно змінилося, збережений хвіст ще старого розміру): не гола
            // помилка, а одразу крок вибору зсуву для цього ж шаблону.
            return "redirect:/schedule/" + ym + "/generate-next/offset?templateId=" + template.id();
        }
        return generateAndSave(from, generated, template, principal);
    }

    /** Крок 1 майстра (лише коли шаблонів під K декілька) — який шаблон застосувати, з наочним прев'ю. */
    @GetMapping("/schedule/{ym}/generate-next/templates")
    public String chooseTemplate(@PathVariable String ym, Model model) {
        YearMonth from = MonthPath.parse(ym);
        DutySchedule current = requireCurrent(from, ym);
        List<RotationTemplate> candidates = templatesForSlots(rotatingCount(current));
        if (candidates.size() < 2) {
            // Шаблон уже однозначний (чи зник) — сюди більше нема сенсу заходити.
            return "redirect:/schedule/" + ym;
        }

        model.addAttribute("ym", ym);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(from.plusMonths(1).getMonth()) + " " + from.plusMonths(1).getYear());
        model.addAttribute("templates", candidates);
        return "schedule-generate-templates";
    }

    /** Крок 2 майстра — з якого дня періоду обраного шаблону почати, з візуалізацією кожного варіанту. */
    @GetMapping("/schedule/{ym}/generate-next/offset")
    public String chooseOffset(@PathVariable String ym, @RequestParam int templateId, Model model) {
        YearMonth from = MonthPath.parse(ym);
        DutySchedule current = requireCurrent(from, ym);
        RotationTemplate template = requireTemplate(templateId);

        List<OffsetOption> options = new ArrayList<>(template.period());
        for (int offset = 0; offset < template.period(); offset++) {
            DutySchedule preview;
            try {
                preview = DutyScheduleGenerator.generateFromOffset(current, template, offset);
            } catch (ScheduleGenerationException e) {
                continue; // K не збігається — сюди майстер і не мав би довести, але про всяк випадок пропускаємо
            }
            options.add(new OffsetOption(offset, previewRows(current, preview, template)));
        }

        model.addAttribute("ym", ym);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(from.plusMonths(1).getMonth()) + " " + from.plusMonths(1).getYear());
        model.addAttribute("template", template);
        model.addAttribute("options", options);
        return "schedule-generate-offset";
    }

    /** Крок 3 майстра — власне генерація з обраного шаблону й зсуву, без пошуку фази. */
    @PostMapping("/schedule/{ym}/generate-next/offset")
    public String generateWithOffset(@PathVariable String ym, @RequestParam int templateId, @RequestParam int offset,
                                      Principal principal, RedirectAttributes redirectAttributes) {
        YearMonth from = MonthPath.parse(ym);
        DutySchedule current = requireCurrent(from, ym);
        if (targetAlreadyExists(from, ym, redirectAttributes)) {
            return "redirect:/schedule/" + ym;
        }
        RotationTemplate template = requireTemplate(templateId);
        try {
            DutySchedule generated = DutyScheduleGenerator.generateFromOffset(current, template, offset);
            return generateAndSave(from, generated, template, principal);
        } catch (ScheduleGenerationException e) {
            redirectAttributes.addFlashAttribute("generationError", e.getMessage());
            return "redirect:/schedule/" + MonthPath.format(from);
        }
    }

    private String generateAndSave(YearMonth from, DutySchedule generated, RotationTemplate template, Principal principal) {
        YearMonth target = from.plusMonths(1);
        String username = principal != null ? principal.getName() : "невідомий";
        repository.save(generated,
                "Згенеровано графік " + MonthPath.format(target) + " на основі " + MonthPath.format(from)
                        + " за шаблоном «" + template.name() + "» (" + username + ")",
                username, username + "@duty.local");
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

    private DutySchedule requireCurrent(YearMonth from, String ym) {
        return repository.find(from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає графіка за " + ym));
    }

    private RotationTemplate requireTemplate(int templateId) {
        return templateRepository.find(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Немає шаблону №" + templateId));
    }

    private boolean targetAlreadyExists(YearMonth from, String ym, RedirectAttributes redirectAttributes) {
        YearMonth target = from.plusMonths(1);
        if (!repository.exists(target)) {
            return false;
        }
        redirectAttributes.addFlashAttribute("generationError",
                "Графік за " + MonthPath.format(target) + " вже існує — спершу видали його, якщо хочеш перегенерувати.");
        return true;
    }

    private static int rotatingCount(DutySchedule schedule) {
        return (int) schedule.engineers().stream().filter(e -> !e.onlyWorkdays()).count();
    }

    private List<RotationTemplate> templatesForSlots(int slots) {
        return templateRepository.findAll().stream().filter(t -> t.slots() == slots).toList();
    }

    /** Рядок прев'ю на кожного ротаційного адміністратора — хвіст поточного місяця + початок продовження цим зсувом. */
    private List<PreviewRow> previewRows(DutySchedule current, DutySchedule preview, RotationTemplate template) {
        List<Engineer> rotating = current.engineers().stream()
                .filter(e -> !e.onlyWorkdays())
                .sorted((a, b) -> Integer.compare(a.number(), b.number()))
                .toList();

        List<DutyDay> tailDays = lastDays(current.days(), PREVIEW_DAYS);
        List<DutyDay> headDays = firstDays(preview.days(), PREVIEW_DAYS);

        List<PreviewRow> rows = new ArrayList<>(rotating.size());
        for (Engineer e : rotating) {
            List<PreviewCell> tail = tailDays.stream().map(d -> cellFor(d, e)).toList();
            List<PreviewCell> head = headDays.stream().map(d -> cellFor(d, e)).toList();
            rows.add(new PreviewRow(e.name(), tail, head));
        }
        return rows;
    }

    private static PreviewCell cellFor(DutyDay day, Engineer e) {
        var mark = day.markFor(e.number());
        return new PreviewCell(day.day(), mark.code(), mark.cssClass(), day.isWeekend());
    }

    private static List<DutyDay> lastDays(List<DutyDay> days, int count) {
        return days.subList(Math.max(0, days.size() - count), days.size());
    }

    private static List<DutyDay> firstDays(List<DutyDay> days, int count) {
        return days.subList(0, Math.min(count, days.size()));
    }

    public record OffsetOption(int offset, List<PreviewRow> rows) {
    }

    public record PreviewRow(String engineerName, List<PreviewCell> tail, List<PreviewCell> head) {
    }

    public record PreviewCell(int day, char code, String cssClass, boolean weekend) {
    }
}
