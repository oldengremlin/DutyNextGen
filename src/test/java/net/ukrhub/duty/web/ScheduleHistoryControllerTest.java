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
package net.ukrhub.duty.web;

import net.ukrhub.duty.domain.DutyDay;
import net.ukrhub.duty.domain.DutyMark;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.Engineer;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleHistoryControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DutyScheduleRepository repository;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
    }

    private TestRestTemplate authed() {
        Path usersFile = tempDir.resolve("config").resolve("users.txt");
        if (!usersFile.toFile().exists()) {
            String hash = new BCryptPasswordEncoder().encode("secret123");
            net.ukrhub.duty.auth.UserStoreTestHelper.writeAdmin(usersFile, "noc", hash);
        }
        return restTemplate.withBasicAuth("noc", "secret123");
    }

    @Test
    void showsAuthorAndDiffForEachChange() {
        YearMonth month = YearMonth.of(2034, 2);
        DutySchedule v1 = new DutySchedule(
                month,
                List.of(new Engineer(1, "Марченко І.", false)),
                List.of(new DutyDay(1, DayOfWeek.WEDNESDAY, Map.of(1, DutyMark.WORK))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(v1, "початковий графік", "Марченко І.", "marchenko@example.com");

        DutySchedule v2 = new DutySchedule(
                month,
                List.of(new Engineer(1, "Марченко І.", false)),
                List.of(new DutyDay(1, DayOfWeek.WEDNESDAY, Map.of(1, DutyMark.DUTY))),
                Map.of(1, DutyMark.OFF),
                Map.of(1, DutyMark.OFF)
        );
        repository.save(v2, "поставили чергування", "Кулинич А.", "kulynych@example.com");

        ResponseEntity<String> response = authed().getForEntity("/schedule/203402/history", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Кулинич А.");
        assertThat(response.getBody()).contains("поставили чергування");
        assertThat(response.getBody()).contains("Марченко І.");
        assertThat(response.getBody()).contains("початковий графік");
    }

    @Test
    void emptyHistoryShowsFriendlyMessage() {
        ResponseEntity<String> response = authed().getForEntity("/schedule/210002/history", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("ще нема жодного запису");
    }
}
