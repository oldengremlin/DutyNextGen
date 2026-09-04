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
package net.ukrhub.duty.schedule;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.git.GitCommitService;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Читання й збереження місячних файлів графіка. Кожне {@link #save} —
 * запис файлу плюс один git-коміт (внутрішній журнал, {@link GitCommitService}).
 */
@Repository
public class DutyScheduleRepository {

    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyyMM");

    private final Path dataDir;
    private final GitCommitService gitCommitService;

    /**
     * Каталог даних фіксується на старті — уже абсолютним
     * ({@link DutyProperties#dataDirPath()}), як того потребує зовнішній git-процес.
     */
    public DutyScheduleRepository(DutyProperties properties, GitCommitService gitCommitService) {
        this.dataDir = properties.dataDirPath();
        this.gitCommitService = gitCommitService;
    }

    /**
     * Графік місяця, або порожньо, якщо файлу нема (штатно — місяць ще не
     * згенеровано).
     *
     * @throws java.io.UncheckedIOException якщо файл є, але не читається
     */
    public Optional<DutySchedule> find(YearMonth month) {
        Path file = fileFor(month);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(DutyScheduleFormat.parse(month, content));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
    }

    /**
     * Записує файл і одразу комітить його — одна дія користувача, один коміт.
     *
     * @param authorName ім'я для авторства git-коміту: так у журналі змін видно
     *        не «застосунок», а конкретну людину, яка натиснула «Зберегти»
     */
    public void save(DutySchedule schedule, String commitMessage, String authorName, String authorEmail) {
        Path file = fileFor(schedule.month());
        String content = DutyScheduleFormat.serialize(schedule);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
        gitCommitService.commit(dataDir, file, commitMessage, authorName, authorEmail);
    }

    /** Чи є файл графіка за цей місяць — без читання й розбору. */
    public boolean exists(YearMonth month) {
        return Files.exists(fileFor(month));
    }

    /** Місяці графіка (за наявними файлами), не раніші за {@code from}, за зростанням. */
    public List<YearMonth> existingMonthsFrom(YearMonth from) {
        if (!Files.isDirectory(dataDir)) {
            return List.of();
        }
        try (var files = Files.list(dataDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("\\d{6}"))
                    .map(name -> YearMonth.parse(name, FILE_NAME))
                    .filter(month -> !month.isBefore(from))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + dataDir, e);
        }
    }

    /** Видаляє файли графіка за перелічені місяці одним git-комітом. */
    public void delete(List<YearMonth> months, String commitMessage, String authorName, String authorEmail) {
        if (months.isEmpty()) {
            return;
        }
        List<Path> files = months.stream().map(this::fileFor).toList();
        gitCommitService.delete(dataDir, files, commitMessage, authorName, authorEmail);
    }

    /**
     * Шлях до місячного файлу ({@code <data-dir>/YYYYMM}) — потрібен назовні
     * для git-історії та для запису кількох місяців одним комітом.
     */
    public Path fileFor(YearMonth month) {
        return dataDir.resolve(FILE_NAME.format(month));
    }

    /** Каталог даних — корінь git-репозиторію журналу змін. */
    public Path dataDir() {
        return dataDir;
    }
}
