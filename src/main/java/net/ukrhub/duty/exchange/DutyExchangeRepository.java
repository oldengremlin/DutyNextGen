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
package net.ukrhub.duty.exchange;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.git.GitCommitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Читання й збереження файлів пропозицій обміну. Той самий підхід, що й
 * {@code RotationTemplateRepository}: файл + один git-коміт на збереження
 * ({@link GitCommitService}) — ім'я файлу це id (як і в шаблонів), а не
 * П.І.Б. учасників.
 */
@Repository
public class DutyExchangeRepository {

    private static final Logger log = LoggerFactory.getLogger(DutyExchangeRepository.class);

    private static final String FILE_NAME_PATTERN = "\\d+";

    private final Path exchangesDir;
    private final GitCommitService gitCommitService;

    /** Каталог пропозицій — свій git-журнал, той самий {@link GitCommitService}, що й для графіка. */
    public DutyExchangeRepository(DutyProperties properties, GitCommitService gitCommitService) {
        this.exchangesDir = properties.exchangesDirPath();
        this.gitCommitService = gitCommitService;
    }

    /** Усі пропозиції, за id (порядком створення). */
    public List<DutyExchangeProposal> findAll() {
        if (!Files.isDirectory(exchangesDir)) {
            return List.of();
        }
        try (var files = Files.list(exchangesDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(FILE_NAME_PATTERN))
                    .map(Integer::parseInt)
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingInt(DutyExchangeProposal::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + exchangesDir, e);
        }
    }

    /**
     * Пропозиція за номером, або порожньо — і коли файлу нема, і коли він
     * пошкоджений (див. коментар у тілі).
     *
     * @throws java.io.UncheckedIOException якщо файл є, але не читається
     */
    public Optional<DutyExchangeProposal> find(int id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + file, e);
        }
        try {
            return Optional.of(DutyExchangeFormat.parse(id, content));
        } catch (RuntimeException e) {
            // Один пошкоджений файл не має валити ВСЕ: findAll() читають
            // і /exchange, і DutyExchangeNoticeAdvice — а той висить
            // @ControllerAdvice-ом на кожній сторінці застосунку, тож
            // виняток звідси раніше означав 500 на будь-якому URL,
            // включно з переглядом графіка.
            log.error("Skipping malformed exchange proposal file {}", file, e);
            return Optional.empty();
        }
    }

    /**
     * Записує пропозицію й комітить її — кожен перехід стану лишає слід у
     * журналі, з автором тієї дії.
     */
    public void save(DutyExchangeProposal proposal, String commitMessage, String authorName, String authorEmail) {
        Path file = fileFor(proposal.id());
        String content = DutyExchangeFormat.serialize(proposal);
        try {
            Files.createDirectories(exchangesDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
        gitCommitService.commit(exchangesDir, file, commitMessage, authorName, authorEmail);
    }

    /** Видаляє запис остаточно (наприклад, коли учасник підтверджує "Зрозуміло" на завершеній пропозиції). */
    public void delete(int id, String commitMessage, String authorName, String authorEmail) {
        gitCommitService.delete(exchangesDir, List.of(fileFor(id)), commitMessage, authorName, authorEmail);
    }

    /**
     * Наступний вільний id — за іменами файлів, без читання й розбору
     * їхнього вмісту: id це і є ім'я файлу. Через {@link #findAll()} це
     * коштувало б повного розбору кожної наявної пропозиції заради одного
     * числа — і, що гірше, пошкоджений (пропущений) файл віддав би id, що
     * вже зайнятий, а нова пропозиція мовчки затерла б його.
     */
    public int nextId() {
        if (!Files.isDirectory(exchangesDir)) {
            return 1;
        }
        try (var files = Files.list(exchangesDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(FILE_NAME_PATTERN))
                    .mapToInt(Integer::parseInt)
                    .max().orElse(0) + 1;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + exchangesDir, e);
        }
    }

    /** Ім'я файлу — сам номер пропозиції (не П.І.Б. учасників). */
    private Path fileFor(int id) {
        return exchangesDir.resolve(String.valueOf(id));
    }
}
