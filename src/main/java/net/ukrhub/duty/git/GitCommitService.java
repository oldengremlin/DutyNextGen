package net.ukrhub.duty.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Внутрішній, непомітний для користувача журнал змін графіка: кожне
 * збереження — окремий git-коміт. Викликає {@code git} як зовнішній
 * процес (не бібліотеку) — для прозорості й простоти аудиту
 * (CLAUDE.md).
 *
 * <p>У каталозі даних, який уже перебуває всередині якогось git-репозиторію
 * (як під час розробки — {@code data/duty} у цьому репозиторії), git
 * сам знаходить той репозиторій вище по дереву каталогів, і коміти йдуть
 * туди. Якщо каталог даних — окремий зовнішній том (типовий production-
 * розгортання в Docker) і жодного репозиторію над ним нема, перед першим
 * комітом там ініціалізується власний, локальний для цього тому git-репозиторій.
 */
@Service
public class GitCommitService {

    private static final Logger log = LoggerFactory.getLogger(GitCommitService.class);
    private static final DateTimeFormatter GIT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    public GitCommitService() {
        // ProcessBuilder передає аргументи й змінні середовища дочірньому
        // процесу через sun.jnu.encoding (native-кодування JVM), а не
        // file.encoding — навіть коли Java-рядки в пам'яті коректний UTF-8.
        // Якщо JVM запущено без UTF-8-локалі (LANG/LC_ALL не задані або не
        // *.UTF-8), кириличні імена авторів і повідомлення комітів
        // мовчки пошкодяться при передачі в git. Краще впасти одразу на
        // старті, ніж тихо писати биту історію.
        String nativeEncoding = System.getProperty("sun.jnu.encoding", "");
        if (!nativeEncoding.toUpperCase(Locale.ROOT).contains("UTF")) {
            throw new IllegalStateException(
                    "JVM запущено з sun.jnu.encoding=" + nativeEncoding + " (не UTF-8). "
                    + "Кириличні автори/повідомлення git-комітів будуть пошкоджені. "
                    + "Встановіть змінну середовища LANG (наприклад, LANG=C.UTF-8 або uk_UA.UTF-8) "
                    + "ПЕРЕД запуском застосунку і перезапустіть його."
            );
        }
    }

    public void commit(Path dataDir, Path fileToCommit, String message, String authorName, String authorEmail) {
        ensureRepo(dataDir);

        String relativePath = dataDir.relativize(fileToCommit).toString();
        run(dataDir, List.of("git", "-C", dataDir.toString(), "add", "--", relativePath));

        String gitDate = OffsetDateTime.now().format(GIT_DATE);
        commitStagedChanges(dataDir, List.of("git", "-C", dataDir.toString(), "commit", "--quiet", "-m", message,
                "--", relativePath), authorName, authorEmail, gitDate);
    }

    /** Видаляє перелічені файли одним комітом (наприклад, каскадне видалення майбутніх місяців). */
    public void delete(Path dataDir, List<Path> filesToDelete, String message, String authorName, String authorEmail) {
        ensureRepo(dataDir);

        List<String> relativePaths = filesToDelete.stream()
                .map(f -> dataDir.relativize(f).toString())
                .toList();

        List<String> rmCommand = new ArrayList<>(List.of("git", "-C", dataDir.toString(),
                "rm", "--quiet", "--ignore-unmatch", "--"));
        rmCommand.addAll(relativePaths);
        run(dataDir, rmCommand);

        String gitDate = OffsetDateTime.now().format(GIT_DATE);
        List<String> commitCommand = new ArrayList<>(List.of("git", "-C", dataDir.toString(),
                "commit", "--quiet", "-m", message, "--"));
        commitCommand.addAll(relativePaths);
        commitStagedChanges(dataDir, commitCommand, authorName, authorEmail, gitDate);
    }

    /**
     * Історія комітів конкретного файлу — новіші перші, як віддає
     * {@code git log}. {@code --follow}, щоб не губити історію, якщо
     * файл колись перейменовувався.
     */
    public List<CommitInfo> history(Path dataDir, Path file) {
        String relativePath = dataDir.relativize(file).toString();
        ProcessResult logResult = execute(dataDir, List.of("git", "-C", dataDir.toString(),
                "log", "--follow", "--date=iso-strict", "--pretty=format:%H%x1f%an%x1f%ad%x1f%s",
                "--", relativePath), null, null, null);
        if (logResult.exitCode() != 0) {
            return List.of();
        }

        List<CommitInfo> commits = new ArrayList<>();
        for (String line : logResult.output().split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\u001F", 4);
            if (parts.length < 4) {
                continue;
            }
            String hash = parts[0];
            commits.add(new CommitInfo(hash, parts[1], parts[2], parts[3], diffFor(dataDir, hash, relativePath)));
        }
        return commits;
    }

    private String diffFor(Path dataDir, String hash, String relativePath) {
        ProcessResult result = execute(dataDir, List.of("git", "-C", dataDir.toString(),
                "show", "--pretty=format:", hash, "--", relativePath), null, null, null);
        return result.exitCode() == 0 ? result.output().strip() : "";
    }

    private void ensureRepo(Path dataDir) {
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося створити каталог даних " + dataDir, e);
        }

        ProcessResult check = execute(dataDir, List.of("git", "-C", dataDir.toString(),
                "rev-parse", "--is-inside-work-tree"), null, null, null);
        if (check.exitCode() == 0) {
            return;
        }

        log.info("У {} немає git-репозиторію — ініціалізую новий (типово для окремого зовнішнього тому)", dataDir);
        run(dataDir, List.of("git", "-C", dataDir.toString(), "init", "--quiet"));
    }

    private void run(Path cwd, List<String> command) {
        run(cwd, command, null, null, null);
    }

    private void run(Path cwd, List<String> command, String authorName, String authorEmail, String gitDate) {
        ProcessResult result = execute(cwd, command, authorName, authorEmail, gitDate);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Команда %s завершилась з кодом %d: %s"
                    .formatted(command, result.exitCode(), result.output()));
        }
    }

    /**
     * {@code git commit} із тим самим шляхом, який щойно пройшов через
     * {@code git add}, може завершитись кодом 1 і "nothing to commit"/
     * "nothing added to commit" — не помилка, а нешкідливий випадок: те,
     * що записав {@code Files.writeString}, виявилось побайтово тим
     * самим, що вже закомічено (наприклад, повторне збереження без
     * реальних змін). Раніше це трактувалось як фатальна помилка
     * ({@code run()}) — реальний випадок: збереження шаблону ротації
     * падало з 500, хоча файл на диску коректно записувався.
     */
    private void commitStagedChanges(Path cwd, List<String> command, String authorName, String authorEmail, String gitDate) {
        ProcessResult result = execute(cwd, command, authorName, authorEmail, gitDate);
        if (result.exitCode() == 0) {
            return;
        }
        if (result.output().contains("nothing to commit") || result.output().contains("nothing added to commit")) {
            log.debug("Немає реальних змін для коміту в {} — пропускаю ({})", cwd, command);
            return;
        }
        throw new IllegalStateException("Команда %s завершилась з кодом %d: %s"
                .formatted(command, result.exitCode(), result.output()));
    }

    private ProcessResult execute(Path cwd, List<String> command, String authorName, String authorEmail, String gitDate) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true);

            if (authorName != null) {
                pb.environment().put("GIT_AUTHOR_NAME", authorName);
                pb.environment().put("GIT_AUTHOR_EMAIL", authorEmail);
                pb.environment().put("GIT_AUTHOR_DATE", gitDate);
                pb.environment().put("GIT_COMMITTER_NAME", authorName);
                pb.environment().put("GIT_COMMITTER_EMAIL", authorEmail);
                pb.environment().put("GIT_COMMITTER_DATE", gitDate);
            }

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, output);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося виконати " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Перервано під час виконання " + command, e);
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
