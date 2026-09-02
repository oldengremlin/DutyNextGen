package net.ukrhub.duty.web;

import net.ukrhub.duty.git.CommitInfo;
import net.ukrhub.duty.git.GitCommitService;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Історія змін місячного файлу графіка — хто, коли і що саме змінив.
 * Дані вже є в git-журналі ({@link GitCommitService}), тут лише
 * показуємо їх. Доступно будь-якому автентифікованому користувачу — та
 * сама видимість, що й у самого перегляду графіка.
 */
@Controller
public class ScheduleHistoryController {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final DutyScheduleRepository repository;
    private final GitCommitService gitCommitService;

    public ScheduleHistoryController(DutyScheduleRepository repository, GitCommitService gitCommitService) {
        this.repository = repository;
        this.gitCommitService = gitCommitService;
    }

    public record HistoryEntry(String hash, String author, String displayDate, String message,
                                List<DiffLine> diffLines) {
    }

    @GetMapping("/schedule/{ym}/history")
    public String history(@PathVariable String ym, Model model) {
        YearMonth month = MonthPath.parse(ym);
        var file = repository.fileFor(month);

        List<CommitInfo> commits = gitCommitService.history(repository.dataDir(), file);
        List<HistoryEntry> entries = commits.stream()
                .map(c -> new HistoryEntry(c.hash().substring(0, Math.min(8, c.hash().length())), c.author(),
                        formatDate(c.date()), c.message(), DiffLine.parse(c.diff())))
                .toList();

        model.addAttribute("month", month);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
        model.addAttribute("entries", entries);

        return "schedule-history";
    }

    private static String formatDate(String isoDate) {
        try {
            return OffsetDateTime.parse(isoDate).format(DISPLAY_DATE);
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }
}
