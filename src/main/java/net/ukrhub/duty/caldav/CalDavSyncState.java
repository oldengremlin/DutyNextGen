package net.ukrhub.duty.caldav;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Стан синку одного місяця: які UID уже опубліковані в CalDAV і з яким
 * хешем вмісту (щоб не смикати сервер PUT'ом, якщо день не змінився) —
 * той самий сенс, що й у двох видів файлів застарілого
 * {@code duty-caldav-sync} ({@code published-$ym.list} + {@code hash-$uid}
 * по одному на UID), в одному файлі на місяць — рядок на UID.
 */
final class CalDavSyncState {

    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyyMM");

    private CalDavSyncState() {
    }

    static Map<String, String> read(Path stateDir, YearMonth month) {
        Path file = fileFor(stateDir, month);
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 2);
                if (parts.length == 2) {
                    result.put(parts[0], parts[1]);
                }
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося прочитати " + file, e);
        }
    }

    static void write(Path stateDir, YearMonth month, Map<String, String> uidToHash) {
        Path file = fileFor(stateDir, month);
        try {
            Files.createDirectories(stateDir);
            StringBuilder sb = new StringBuilder();
            for (var entry : uidToHash.entrySet()) {
                sb.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не вдалося записати " + file, e);
        }
    }

    private static Path fileFor(Path stateDir, YearMonth month) {
        return stateDir.resolve("published-" + FILE_NAME.format(month) + ".txt");
    }
}
