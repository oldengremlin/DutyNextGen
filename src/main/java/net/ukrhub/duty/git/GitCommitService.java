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
package net.ukrhub.duty.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /** ASCII RS — межа між комітами у вивод і {@code git log --patch} ({@link #history}). */
    private static final String RECORD_SEPARATOR = "\u001E";

    /** ASCII US — межа між полями заголовка коміту (те саме, що {@code %x1f} у pretty-format). */
    private static final String UNIT_SEPARATOR = "\u001F";

    /**
     * Абсолютний шлях замість голого {@code "git"}: спостережено в
     * production, що спавн процесу з додатковими env-змінними (автор/
     * дата коміту) зрідка падає з "Exec failed, error: 2 (No such file
     * or directory)" саме там, де без них той самий каталог і той самий
     * бінарник щойно відпрацював штатно (наприклад, {@code git rm} перед
     * {@code git commit} у {@link #delete}) — це збігається з тим, як
     * exec шукає команду через {@code PATH} з переданого envp. Абсолютний
     * шлях прибирає саму потребу в цьому пошуку.
     */
    private static final String GIT_EXECUTABLE = resolveGitExecutable();

    /**
     * Абсолютний шлях до {@code git} серед типових місць; жодного не знайшли —
     * лишається голе {@code "git"} з пошуком через {@code PATH} (краще, ніж
     * відмовити на старті: у нетиповому образі бінарник може лежати деінде).
     */
    private static String resolveGitExecutable() {
        for (String candidate : List.of("/usr/bin/git", "/usr/local/bin/git", "/bin/git")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "git";
    }

    /**
     * Перевіряє на старті, що JVM запущено в UTF-8-локалі.
     *
     * @throws IllegalStateException якщо ні — краще не піднятись узагалі, ніж
     *         тихо писати git-історію з пошкодженою кирилицею
     */
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

    /**
     * Один файл — один коміт: {@code git add} і {@code git commit} саме цього
     * шляху, від імені вказаного автора.
     *
     * @param dataDir корінь репозиторію журналу; створюється й ініціалізується
     *        при потребі ({@link #ensureRepo})
     */
    public void commit(Path dataDir, Path fileToCommit, String message, String authorName, String authorEmail) {
        ensureRepo(dataDir);

        String relativePath = dataDir.relativize(fileToCommit).toString();
        run(dataDir, List.of(GIT_EXECUTABLE, "-C", dataDir.toString(), "add", "--", relativePath));

        String gitDate = OffsetDateTime.now().format(GIT_DATE);
        commitStagedChanges(dataDir, List.of(GIT_EXECUTABLE, "-C", dataDir.toString(), "commit", "--quiet", "-m", message,
                "--", relativePath), authorName, authorEmail, gitDate);
    }

    /**
     * Той самий {@link #commit(Path, Path, String, String, String)}, але
     * для кількох файлів одним комітом — щоб дія, яка чіпає більше ніж
     * один файл (наприклад, обмін чергуваннями між двома різними
     * місячними файлами графіка), не могла лишитись застосованою лише
     * наполовину.
     */
    public void commit(Path dataDir, List<Path> filesToCommit, String message, String authorName, String authorEmail) {
        ensureRepo(dataDir);

        List<String> relativePaths = filesToCommit.stream()
                .map(f -> dataDir.relativize(f).toString())
                .toList();

        List<String> addCommand = new ArrayList<>(List.of(GIT_EXECUTABLE, "-C", dataDir.toString(), "add", "--"));
        addCommand.addAll(relativePaths);
        run(dataDir, addCommand);

        String gitDate = OffsetDateTime.now().format(GIT_DATE);
        List<String> commitCommand = new ArrayList<>(List.of(GIT_EXECUTABLE, "-C", dataDir.toString(),
                "commit", "--quiet", "-m", message, "--"));
        commitCommand.addAll(relativePaths);
        commitStagedChanges(dataDir, commitCommand, authorName, authorEmail, gitDate);
    }

    /** Видаляє перелічені файли одним комітом (наприклад, каскадне видалення майбутніх місяців). */
    public void delete(Path dataDir, List<Path> filesToDelete, String message, String authorName, String authorEmail) {
        ensureRepo(dataDir);

        List<String> relativePaths = filesToDelete.stream()
                .map(f -> dataDir.relativize(f).toString())
                .toList();

        List<String> rmCommand = new ArrayList<>(List.of(GIT_EXECUTABLE, "-C", dataDir.toString(),
                "rm", "--quiet", "--ignore-unmatch", "--"));
        rmCommand.addAll(relativePaths);
        run(dataDir, rmCommand);

        String gitDate = OffsetDateTime.now().format(GIT_DATE);
        List<String> commitCommand = new ArrayList<>(List.of(GIT_EXECUTABLE, "-C", dataDir.toString(),
                "commit", "--quiet", "-m", message, "--"));
        commitCommand.addAll(relativePaths);
        try {
            commitStagedChanges(dataDir, commitCommand, authorName, authorEmail, gitDate);
        } catch (RuntimeException e) {
            // git rm вище вже видалив файли з робочого каталогу — на
            // відміну від git add (commit()), це не відкладена дія до
            // коміту. Якщо сам коміт не пройшов, файли лишаться фізично
            // видаленими, а git-історія — так і не оновленою: тиха
            // втрата даних, замаскована повідомленням про невдачу.
            // Відновлюємо робочий каталог і індекс до стану останнього
            // коміту, щоб дію справді можна було безпечно повторити.
            restoreFromHead(dataDir, relativePaths);
            throw e;
        }
    }

    /**
     * Повертає робочий каталог і індекс до стану останнього коміту. Невдача
     * тут — не виняток, а {@code ERROR} у лог: викликач уже кидає власний,
     * важливіший, і глушити його нема сенсу.
     */
    private void restoreFromHead(Path dataDir, List<String> relativePaths) {
        List<String> restoreCommand = new ArrayList<>(List.of(GIT_EXECUTABLE, "-C", dataDir.toString(),
                "checkout", "--quiet", "HEAD", "--"));
        restoreCommand.addAll(relativePaths);
        try {
            run(dataDir, restoreCommand);
        } catch (RuntimeException restoreFailure) {
            log.error("Failed to restore {} in {} after a failed deletion commit — "
                    + "manual recovery required (git checkout HEAD -- ...)", relativePaths, dataDir, restoreFailure);
        }
    }

    /**
     * Історія комітів конкретного файлу — новіші перші, як віддає
     * {@code git log}. {@code --follow}, щоб не губити історію, якщо
     * файл колись перейменовувався.
     */
    public List<CommitInfo> history(Path dataDir, Path file) {
        String relativePath = dataDir.relativize(file).toString();
        // Один git-процес на всю історію разом з діффами, а не "git log"
        // плюс окремий "git show" на кожен коміт: у файлу, який редагують
        // кілька разів на місяць, за пару років набігають сотні комітів —
        // тобто сотні fork+exec на одне відкриття сторінки історії, у
        // середовищі, де сам спавн git-процесу вже показав себе ненадійним
        // (див. startWithRetry нижче). RECORD_SEPARATOR перед %H дає
        // однозначну межу записів: діфф може містити будь-що, зокрема й
        // рядки, схожі на заголовок коміту.
        ProcessResult logResult = execute(dataDir, List.of(GIT_EXECUTABLE, "-C", dataDir.toString(),
                "log", "--follow", "--date=iso-strict", "--patch", "--no-color",
                "--pretty=format:" + RECORD_SEPARATOR + "%H%x1f%an%x1f%ad%x1f%s",
                "--", relativePath), null, null, null);
        if (logResult.exitCode() != 0) {
            return List.of();
        }

        List<CommitInfo> commits = new ArrayList<>();
        for (String record : logResult.output().split(RECORD_SEPARATOR)) {
            CommitInfo commit = parseHistoryRecord(record);
            if (commit != null) {
                commits.add(commit);
            }
        }
        return commits;
    }

    /**
     * Один запис виводу {@code git log --patch}: перший рядок — заголовок
     * з полями через {@code \u001F} (той самий {@code %x1f} з
     * pretty-format), решта — діфф саме цього коміту.
     *
     * @return {@code null}, якщо запис порожній чи заголовок неповний
     *         (перший «запис» перед першим роздільником — завжди порожній)
     */
    private static CommitInfo parseHistoryRecord(String record) {
        if (record.isBlank()) {
            return null;
        }
        String[] headerAndDiff = record.split("\n", 2);
        String[] fields = headerAndDiff[0].split(UNIT_SEPARATOR, 4);
        if (fields.length < 4) {
            return null;
        }
        String diff = headerAndDiff.length > 1 ? headerAndDiff[1].strip() : "";
        return new CommitInfo(fields[0], fields[1], fields[2], fields[3], diff);
    }

    /**
     * Створює каталог даних і, якщо він не всередині жодного git-репозиторію,
     * ініціалізує там власний. Каталог усередині наявного репозиторію
     * (як під час розробки) лишається як є — коміти йдуть у той репозиторій.
     */
    private void ensureRepo(Path dataDir) {
        try {
            java.nio.file.Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create data directory " + dataDir, e);
        }

        ProcessResult check = execute(dataDir, List.of(GIT_EXECUTABLE, "-C", dataDir.toString(),
                "rev-parse", "--is-inside-work-tree"), null, null, null);
        if (check.exitCode() == 0) {
            return;
        }

        log.info("No git repository in {} — initializing a new one (expected for a separate external volume)", dataDir);
        run(dataDir, List.of(GIT_EXECUTABLE, "-C", dataDir.toString(), "init", "--quiet"));
    }

    /** Команда без авторства — усе, крім самого {@code git commit}. */
    private void run(Path cwd, List<String> command) {
        run(cwd, command, null, null, null);
    }

    /**
     * Виконує команду, вимагаючи нульового коду завершення.
     *
     * @throws IllegalStateException з кодом і виводом, якщо команда не вдалась
     */
    private void run(Path cwd, List<String> command, String authorName, String authorEmail, String gitDate) {
        ProcessResult result = execute(cwd, command, authorName, authorEmail, gitDate);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Command %s exited with code %d: %s"
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
            log.debug("Nothing to commit in {} — skipping ({})", cwd, command);
            return;
        }
        throw new IllegalStateException("Command %s exited with code %d: %s"
                .formatted(command, result.exitCode(), result.output()));
    }

    /**
     * Запускає git і збирає його вивід (stdout і stderr разом — при розборі
     * помилок важливо бачити обидва в правильному порядку).
     *
     * @param authorName {@code null} — не передавати git жодних змінних
     *        авторства; інакше задаються всі шість {@code GIT_*}
     */
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

            Process process = startWithRetry(pb, command);
            // Явно UTF-8, а не дефолтне кодування JVM: git віддає кириличні
            // імена авторів і повідомлення комітів саме в ньому, і читати їх
            // "як вийде" — та сама помилка, від якої конструктор вище
            // захищає на боці запису.
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, output);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to execute " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing " + command, e);
        }
    }

    private static final int START_ATTEMPTS = 5;

    /**
     * У production (Docker) зрідка спостерігався збій самого
     * {@code pb.start()} — {@code IOException: Exec failed, error: 2
     * (No such file or directory)} — саме на командах з додатковими
     * env-змінними (автор/дата коміту), тоді як той самий каталог і той
     * самий бінарник мить до того відпрацював штатно без них (наприклад,
     * {@code git rm} перед {@code git commit} у {@link #delete}) —
     * звідси абсолютний {@link #GIT_EXECUTABLE} вище. Локально не
     * відтворюється, тому про всяк випадок — і кілька повторів з
     * наростаючою паузою, разом сумарно понад секунду: у production
     * наступний, повністю незалежний запит за кілька секунд після збою
     * відпрацьовував штатно, тобто яка б умова це не спричиняла, вона не
     * тримається довго.
     */
    private Process startWithRetry(ProcessBuilder pb, List<String> command) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            try {
                return pb.start();
            } catch (IOException e) {
                last = e;
                log.warn("Attempt {} to start {} failed: {}", attempt, command, e.getMessage());
                if (attempt == START_ATTEMPTS) {
                    break;
                }
                try {
                    Thread.sleep(100L * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    /**
     * Результат зовнішнього процесу: код завершення й увесь його вивід
     * (stdout і stderr разом).
     *
     * @param exitCode код завершення git-процесу
     * @param output   увесь вивід процесу
     */
    private record ProcessResult(int exitCode, String output) {
    }
}
