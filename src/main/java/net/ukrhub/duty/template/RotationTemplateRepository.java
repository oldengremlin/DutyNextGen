package net.ukrhub.duty.template;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.git.GitCommitService;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static final String FILE_NAME_PATTERN = "\\d+";

    private final Path templatesDir;
    private final GitCommitService gitCommitService;

    public RotationTemplateRepository(DutyProperties properties, GitCommitService gitCommitService) {
        this.templatesDir = properties.templatesDirPath();
        this.gitCommitService = gitCommitService;
    }

    public List<RotationTemplate> findAll() {
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        try (var files = Files.list(templatesDir)) {
            return files
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.matches(FILE_NAME_PATTERN))
                    .map(Integer::parseInt)
                    .sorted()
                    .map(this::find)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + templatesDir, e);
        }
    }

    public Optional<RotationTemplate> find(int id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(RotationTemplateFormat.parse(id, content));
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + file, e);
        }
    }

    public void save(RotationTemplate template, String commitMessage, String authorName, String authorEmail) {
        Path file = fileFor(template.id());
        String content = RotationTemplateFormat.serialize(template);
        try {
            Files.createDirectories(templatesDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + file, e);
        }
        gitCommitService.commit(templatesDir, file, commitMessage, authorName, authorEmail);
    }

    public void delete(int id, String commitMessage, String authorName, String authorEmail) {
        gitCommitService.delete(templatesDir, List.of(fileFor(id)), commitMessage, authorName, authorEmail);
    }

    public int nextId() {
        return findAll().stream().mapToInt(RotationTemplate::id).max().orElse(0) + 1;
    }

    private Path fileFor(int id) {
        return templatesDir.resolve(String.valueOf(id));
    }
}
