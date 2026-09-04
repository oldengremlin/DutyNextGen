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
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.domain.DutyExchangeStep;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.git.GitCommitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DutyExchangeRepositoryTest {

    private static DutyExchangeRepository repositoryIn(Path tempDir) {
        Path exchangesDir = tempDir.resolve("exchanges");
        DutyProperties properties = new DutyProperties(
                tempDir.resolve("data").toString(), tempDir.resolve("config").toString(), null,
                tempDir.resolve("templates").toString(), exchangesDir.toString());
        return new DutyExchangeRepository(properties, new GitCommitService());
    }

    private static DutyExchangeProposal proposal(int id) {
        return new DutyExchangeProposal(
                id, "Кулинич А.", "kulinich", "Журавльова К.",
                List.of(new DutyExchangeStep(DutyMark.DUTY, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 9))),
                DutyExchangeStatus.PENDING,
                LocalDateTime.of(2026, 9, 4, 15, 30, 0));
    }

    @Test
    void savingProposalWritesFileAndCommits(@TempDir Path tempDir) throws IOException, InterruptedException {
        DutyExchangeRepository repository = repositoryIn(tempDir);

        repository.save(proposal(1), "тестовий коміт", "Тест Тестович", "test@example.com");

        assertThat(tempDir.resolve("exchanges").resolve("1")).exists();
        assertThat(repository.find(1)).contains(proposal(1));

        Process log = new ProcessBuilder("git", "-C", tempDir.resolve("exchanges").toString(),
                "log", "--oneline", "--format=%an|%s").start();
        String output = new String(log.getInputStream().readAllBytes());
        log.waitFor();
        assertThat(output.trim()).isEqualTo("Тест Тестович|тестовий коміт");
    }

    @Test
    void findAllReturnsProposalsSortedById(@TempDir Path tempDir) {
        DutyExchangeRepository repository = repositoryIn(tempDir);
        repository.save(proposal(3), "сід", "Тест", "test@example.com");
        repository.save(proposal(1), "сід", "Тест", "test@example.com");

        assertThat(repository.findAll()).extracting(DutyExchangeProposal::id).containsExactly(1, 3);
    }

    @Test
    void nextIdIsOneMoreThanMaxExisting(@TempDir Path tempDir) {
        DutyExchangeRepository repository = repositoryIn(tempDir);
        assertThat(repository.nextId()).isEqualTo(1);

        repository.save(proposal(1), "сід", "Тест", "test@example.com");
        repository.save(proposal(5), "сід", "Тест", "test@example.com");

        assertThat(repository.nextId()).isEqualTo(6);
    }

    @Test
    void deleteRemovesFileAndCommits(@TempDir Path tempDir) throws IOException, InterruptedException {
        DutyExchangeRepository repository = repositoryIn(tempDir);
        repository.save(proposal(1), "сід", "Тест", "test@example.com");

        repository.delete(1, "видалено", "Тест Тестович", "test@example.com");

        assertThat(tempDir.resolve("exchanges").resolve("1")).doesNotExist();
        assertThat(repository.find(1)).isEmpty();
    }
}
