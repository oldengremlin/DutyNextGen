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
package net.ukrhub.duty.template;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.RotationTemplate;
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
 * Читання й збереження файлів шаблонів ротації. Той самий підхід, що й
 * {@code DutyScheduleRepository}: файл + один git-коміт на збереження
 * ({@link GitCommitService}) — шаблони мають git-історію так само, як і
 * сам графік.
 *
 * <p>Ім'я файлу — числовий id (як і {@code Engineer.number()}), не
 * {@code name()} шаблону: вільний текст користувача ненадійний як ім'я
 * файлу (пробіли, повтори, перейменування) — id стабільний, а
 * відображувану назву можна міняти без перейменування файлу.
 */
@Repository
public class RotationTemplateRepository {

    private static final Logger log = LoggerFactory.getLogger(RotationTemplateRepository.class);

    private static final String FILE_NAME_PATTERN = "\\d+";

    private final Path templatesDir;
    private final GitCommitService gitCommitService;

    /**
     * Каталог шаблонів — окремий git-репозиторій журналу (свій, якщо це
     * окремий том), той самий {@link GitCommitService}, що й для графіка.
     */
    public RotationTemplateRepository(DutyProperties properties, GitCommitService gitCommitService) {
        this.templatesDir = properties.templatesDirPath();
        this.gitCommitService = gitCommitService;
    }

    /**
     * За кількістю слотів (2, 3, 4...), а не за id — так шаблони під
     * однакову кількість чергових видно поруч. У межах однієї кількості
     * слотів — за id (порядком створення): найпростіший передбачуваний
     * тайбрейк, коли шаблонів під те саме K декілька.
     */
    public List<RotationTemplate> findAll() {
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        try (var files = Files.list(templatesDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(FILE_NAME_PATTERN))
                    .map(Integer::parseInt)
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingInt(RotationTemplate::slots).thenComparingInt(RotationTemplate::id))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + templatesDir, e);
        }
    }

    /**
     * Шаблон за номером, або порожньо — і коли файлу нема, і коли він
     * пошкоджений (див. коментар у тілі).
     */
    public Optional<RotationTemplate> find(int id) {
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
            return Optional.of(RotationTemplateFormat.parse(id, content));
        } catch (RuntimeException e) {
            // Той самий підхід, що й у DutyExchangeRepository: пошкоджений
            // файл шаблону пропускаємо з гучним записом у лог, а не валимо
            // весь список шаблонів і майстер генерації разом з ним.
            log.error("Skipping malformed rotation template file {}", file, e);
            return Optional.empty();
        }
    }

    /** Записує файл шаблону й комітить його — так само, як і графік. */
    public void save(RotationTemplate template, String commitMessage, String authorName, String authorEmail) {
        Path file = fileFor(template.id());
        String content = RotationTemplateFormat.serialize(template);
        try {
            Files.createDirectories(templatesDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + file, e);
        }
        gitCommitService.commit(templatesDir, file, commitMessage, authorName, authorEmail);
    }

    /**
     * Видаляє шаблон одним комітом. Наявні місяці з цим {@code [ Tid ]}
     * лишаються як є — фонова генерація просто пропустить наступний місяць із
     * поясненням у лог.
     */
    public void delete(int id, String commitMessage, String authorName, String authorEmail) {
        gitCommitService.delete(templatesDir, List.of(fileFor(id)), commitMessage, authorName, authorEmail);
    }

    /**
     * Наступний вільний id — за іменами файлів, без розбору їхнього
     * вмісту: id це і є ім'я файлу. Той самий мотив, що й у
     * {@code DutyExchangeRepository.nextId()} — і дешевше, і не віддає
     * зайнятий id, коли якийсь файл не вдалося розібрати.
     */
    public int nextId() {
        if (!Files.isDirectory(templatesDir)) {
            return 1;
        }
        try (var files = Files.list(templatesDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(FILE_NAME_PATTERN))
                    .mapToInt(Integer::parseInt)
                    .max().orElse(0) + 1;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + templatesDir, e);
        }
    }

    /** Ім'я файлу — сам номер шаблону (не назва: вільний текст користувача ненадійний як ім'я файлу). */
    private Path fileFor(int id) {
        return templatesDir.resolve(String.valueOf(id));
    }
}
