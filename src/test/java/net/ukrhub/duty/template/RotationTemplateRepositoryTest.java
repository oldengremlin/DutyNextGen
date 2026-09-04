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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RotationTemplateRepositoryTest {

    private static RotationTemplateRepository repositoryIn(Path tempDir) {
        Path templatesDir = tempDir.resolve("templates");
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null,
                templatesDir.toString(), tempDir.resolve("exchanges").toString());
        return new RotationTemplateRepository(properties, new GitCommitService());
    }

    @Test
    void savingTemplateWritesFileAndCommits(@TempDir Path tempDir) throws IOException, InterruptedException {
        RotationTemplateRepository repository = repositoryIn(tempDir);
        RotationTemplate template = new RotationTemplate(1, "Два чергових", List.of("DD--", "--DD"));

        repository.save(template, "тестовий коміт", "Тест Тестович", "test@example.com");

        assertThat(tempDir.resolve("templates").resolve("1")).exists();
        assertThat(repository.find(1)).contains(template);

        Process log = new ProcessBuilder("git", "-C", tempDir.resolve("templates").toString(),
                "log", "--oneline", "--format=%an|%s").start();
        String output = new String(log.getInputStream().readAllBytes());
        log.waitFor();
        assertThat(output.trim()).isEqualTo("Тест Тестович|тестовий коміт");
    }

    /** Реальний випадок: логічніше бачити поруч шаблони під однакову кількість чергових, а не в порядку створення. */
    @Test
    void findAllSortsBySlotsThenById(@TempDir Path tempDir) {
        RotationTemplateRepository repository = repositoryIn(tempDir);
        repository.save(new RotationTemplate(1, "Чотири (перший)", List.of("D---", "-D--", "--D-", "---D")),
                "сід", "Тест", "test@example.com");
        repository.save(new RotationTemplate(2, "Два", List.of("DD--", "--DD")), "сід", "Тест", "test@example.com");
        repository.save(new RotationTemplate(3, "Чотири (другий)", List.of("D-WW", "WD-W", "WWD-", "-WWD")),
                "сід", "Тест", "test@example.com");

        assertThat(repository.findAll()).extracting(RotationTemplate::id).containsExactly(2, 1, 3);
    }

    @Test
    void findAllReturnsTemplatesSortedById(@TempDir Path tempDir) {
        RotationTemplateRepository repository = repositoryIn(tempDir);
        repository.save(new RotationTemplate(2, "Б", List.of("D-")), "сід", "Тест", "test@example.com");
        repository.save(new RotationTemplate(1, "А", List.of("D-")), "сід", "Тест", "test@example.com");

        assertThat(repository.findAll()).extracting(RotationTemplate::id).containsExactly(1, 2);
    }

    @Test
    void nextIdIsOneMoreThanMaxExisting(@TempDir Path tempDir) {
        RotationTemplateRepository repository = repositoryIn(tempDir);
        assertThat(repository.nextId()).isEqualTo(1);

        repository.save(new RotationTemplate(1, "А", List.of("D-")), "сід", "Тест", "test@example.com");
        repository.save(new RotationTemplate(5, "Б", List.of("D-")), "сід", "Тест", "test@example.com");

        assertThat(repository.nextId()).isEqualTo(6);
    }

    @Test
    void deleteRemovesFileAndCommits(@TempDir Path tempDir) throws IOException, InterruptedException {
        RotationTemplateRepository repository = repositoryIn(tempDir);
        repository.save(new RotationTemplate(1, "Тимчасовий", List.of("D-")), "сід", "Тест", "test@example.com");

        repository.delete(1, "видалено тестовий шаблон", "Тест Тестович", "test@example.com");

        assertThat(tempDir.resolve("templates").resolve("1")).doesNotExist();
        assertThat(repository.find(1)).isEmpty();

        Process log = new ProcessBuilder("git", "-C", tempDir.resolve("templates").toString(),
                "log", "-1", "--format=%an|%s").start();
        String output = new String(log.getInputStream().readAllBytes());
        log.waitFor();
        assertThat(output.trim()).isEqualTo("Тест Тестович|видалено тестовий шаблон");
    }
}
