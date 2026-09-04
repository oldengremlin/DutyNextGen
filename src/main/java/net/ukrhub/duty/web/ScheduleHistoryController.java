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

    /**
     * Репозиторій потрібен лише заради шляхів ({@code dataDir}, {@code fileFor})
     * — сам графік тут не читається, усе показане приходить з git-журналу.
     */
    public ScheduleHistoryController(DutyScheduleRepository repository, GitCommitService gitCommitService) {
        this.repository = repository;
        this.gitCommitService = gitCommitService;
    }

    /**
     * Один рядок історії для шаблону: усе вже готове до показу.
     *
     * @param hash        скорочений SHA-1 (8 символів)
     * @param author      хто зберіг
     * @param displayDate дата у вигляді {@code dd.MM.yyyy HH:mm}
     * @param message     повідомлення коміту
     * @param diffLines   діфф, розібраний на кольорові рядки
     */
    public record HistoryEntry(String hash, String author, String displayDate, String message,
                                List<DiffLine> diffLines) {
    }

    /**
     * Сторінка історії. Порожня історія (файлу ще не було в git) — не помилка:
     * показується порожній список.
     */
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

    /**
     * ISO-дата з git у вигляд для читання; нерозбірне значення показуємо як є —
     * краще сира дата, ніж упала сторінка.
     */
    private static String formatDate(String isoDate) {
        try {
            return OffsetDateTime.parse(isoDate).format(DISPLAY_DATE);
        } catch (DateTimeParseException e) {
            return isoDate;
        }
    }
}
