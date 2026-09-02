package net.ukrhub.duty.caldav;

import net.ukrhub.duty.config.DutyProperties;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Синхронізація графіка поточного й наступного місяця з CalDAV
 * (Baikal) — порт {@code duty-caldav-sync}: PUT нових/змінених подій
 * (за хешем вмісту, щоб не смикати сервер даремно), DELETE тих, що
 * зникли з актуального графіка — крім уже минулих, їх ніколи не
 * чіпаємо (сам {@link DutyIcsGenerator} їх і не генерує).
 *
 * <p>Не робить нічого, якщо CalDAV не налаштовано — ні через
 * {@code DUTY_CALDAV_BASE_URL} (та сусідні змінні середовища), ні через
 * файл {@code <config-dir>/duty-caldav.conf} ({@link CaldavConfFile},
 * той самий формат, яким користувався застарілий {@code duty-caldav-sync}).
 * Змінні середовища мають пріоритет над файлом, якщо задано обидва.
 */
@Service
public class CalDavSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalDavSyncService.class);
    private static final DateTimeFormatter YMD8 = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DutyScheduleRepository repository;
    private final DutyProperties.Caldav config;

    public CalDavSyncService(DutyScheduleRepository repository, DutyProperties properties) {
        this.repository = repository;
        this.config = resolveConfig(properties);
    }

    private static DutyProperties.Caldav resolveConfig(DutyProperties properties) {
        DutyProperties.Caldav fromEnv = properties.caldav();
        if (fromEnv != null && fromEnv.configured()) {
            return fromEnv;
        }
        String stateDir = fromEnv != null ? fromEnv.stateDir() : null;
        Optional<DutyProperties.Caldav> fromFile = CaldavConfFile.readIfPresent(properties.configDirPath(), stateDir);
        if (fromFile.isPresent()) {
            log.info("CalDAV налаштовано з {}/duty-caldav.conf", properties.configDirPath());
            return fromFile.get();
        }
        return fromEnv;
    }

    public boolean configured() {
        return config != null && config.configured();
    }

    /** Синхронізує поточний і наступний місяць. Нічого не робить, якщо CalDAV не налаштовано. */
    public void syncCurrentAndNext() {
        if (!configured()) {
            return;
        }
        YearMonth current = YearMonth.now();
        syncMonth(current);
        syncMonth(current.plusMonths(1));
    }

    void syncMonth(YearMonth month) {
        DutySchedule schedule = repository.find(month).orElse(null);
        if (schedule == null) {
            return;
        }

        LocalDate today = LocalDate.now();
        List<IcsEvent> events = DutyIcsGenerator.generate(schedule, today);
        Map<String, String> oldState = CalDavSyncState.read(config.stateDirPath(), month);
        Map<String, String> newState = new LinkedHashMap<>();
        CalDavClient client = new CalDavClient(config.baseUrl(), config.user(), config.password());

        for (IcsEvent event : events) {
            String hash = sha256(event.body());
            if (hash.equals(oldState.get(event.uid()))) {
                newState.put(event.uid(), hash);
                continue;
            }
            try {
                client.put(event.uid(), event.body());
                newState.put(event.uid(), hash);
            } catch (IOException | InterruptedException e) {
                log.warn("Не вдалося опублікувати {} у CalDAV: {}", event.uid(), e.getMessage());
                // Лишаємо попередній стан (якщо був) — наступний прогін спробує ще раз.
                if (oldState.containsKey(event.uid())) {
                    newState.put(event.uid(), oldState.get(event.uid()));
                }
            }
        }

        for (String uid : oldState.keySet()) {
            if (newState.containsKey(uid)) {
                continue;
            }
            LocalDate uidDate = dateFromUid(uid);
            if (uidDate != null && uidDate.isBefore(today)) {
                continue;
            }
            try {
                client.delete(uid);
            } catch (IOException | InterruptedException e) {
                log.warn("Не вдалося видалити {} з CalDAV: {}", uid, e.getMessage());
                newState.put(uid, oldState.get(uid));
            }
        }

        CalDavSyncState.write(config.stateDirPath(), month, newState);
    }

    private static LocalDate dateFromUid(String uid) {
        if (!uid.startsWith("duty-") || uid.length() < 13) {
            return null;
        }
        try {
            return LocalDate.parse(uid.substring(5, 13), YMD8);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Лише для локального виявлення змін (нема потреби у сумісності зі старим md5sum-станом). */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступний у цій JVM", e);
        }
    }
}
