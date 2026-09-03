package net.ukrhub.duty.exchange;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.git.GitCommitService;
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

    private static final String FILE_NAME_PATTERN = "\\d+";

    private final Path exchangesDir;
    private final GitCommitService gitCommitService;

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
            throw new UncheckedIOException("Не вдалося прочитати " + exchangesDir, e);
        }
    }

    public Optional<DutyExchangeProposal> find(int id) {
        Path file = fileFor(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(DutyExchangeFormat.parse(id, content));
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + file, e);
        }
    }

    public void save(DutyExchangeProposal proposal, String commitMessage, String authorName, String authorEmail) {
        Path file = fileFor(proposal.id());
        String content = DutyExchangeFormat.serialize(proposal);
        try {
            Files.createDirectories(exchangesDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + file, e);
        }
        gitCommitService.commit(exchangesDir, file, commitMessage, authorName, authorEmail);
    }

    /** Видаляє запис остаточно (наприклад, коли учасник підтверджує "Зрозуміло" на завершеній пропозиції). */
    public void delete(int id, String commitMessage, String authorName, String authorEmail) {
        gitCommitService.delete(exchangesDir, List.of(fileFor(id)), commitMessage, authorName, authorEmail);
    }

    public int nextId() {
        return findAll().stream().mapToInt(DutyExchangeProposal::id).max().orElse(0) + 1;
    }

    private Path fileFor(int id) {
        return exchangesDir.resolve(String.valueOf(id));
    }
}
