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
package net.ukrhub.duty.caldav;

import net.ukrhub.duty.config.DutyProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Читання {@code <config-dir>/duty-caldav.conf} — того самого файлу
 * (формат {@code KEY="value"}, сумісний з {@code . "$CONF"} у POSIX sh),
 * яким користувався застарілий {@code duty-caldav-sync}
 * ({@code CALDAV_BASE_URL}/{@code CALDAV_USER}/{@code CALDAV_PASS}).
 *
 * <p>Секрети свідомо тримаються поза git саме так, як і в оригіналі —
 * не переносимо їх у {@code DUTY_CALDAV_*} змінні середовища й не
 * вимагаємо від адміністратора чіпати {@code dbuild}/{@code docker run}:
 * файл уже лежить у змонтованому томі {@code /config} за звичкою з
 * попередньої системи, і цього досить.
 *
 * <p>{@code DUTY_DIR}/{@code STATE_DIR} з файлу свідомо ігноруються —
 * це шляхи застарілого shell-скрипту (свій каталог даних, свій формат
 * стану), у nextgen для цього вже є власні {@code duty.data-dir} і
 * {@code duty.caldav.state-dir}.
 */
final class CaldavConfFile {

    private static final String FILE_NAME = "duty-caldav.conf";

    private CaldavConfFile() {
    }

    static Optional<DutyProperties.Caldav> readIfPresent(Path configDir, String stateDir) {
        Path file = configDir.resolve(FILE_NAME);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }

        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                values.put(trimmed.substring(0, eq).strip(), unquote(trimmed.substring(eq + 1).strip()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + file, e);
        }

        String baseUrl = values.get("CALDAV_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new DutyProperties.Caldav(
                baseUrl, values.getOrDefault("CALDAV_USER", ""), values.getOrDefault("CALDAV_PASS", ""), stateDir));
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
