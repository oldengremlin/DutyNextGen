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

    public DutyScheduleRepository(DutyProperties properties, GitCommitService gitCommitService) {
        this.dataDir = properties.dataDirPath();
        this.gitCommitService = gitCommitService;
    }

    public Optional<DutySchedule> find(YearMonth month) {
        Path file = fileFor(month);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(DutyScheduleFormat.parse(month, content));
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + file, e);
        }
    }

    public void save(DutySchedule schedule, String commitMessage, String authorName, String authorEmail) {
        Path file = fileFor(schedule.month());
        String content = DutyScheduleFormat.serialize(schedule);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + file, e);
        }
        gitCommitService.commit(dataDir, file, commitMessage, authorName, authorEmail);
    }

    public Path fileFor(YearMonth month) {
        return dataDir.resolve(FILE_NAME.format(month));
    }

    public Path dataDir() {
        return dataDir;
    }
}
