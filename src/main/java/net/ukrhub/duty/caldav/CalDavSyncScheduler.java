package net.ukrhub.duty.caldav;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Фоновий аналог застарілого {@code duty-caldav-sync} (нескінченний
 * цикл + {@code sleep 300}, без cron) — той самий інтервал, 5 хвилин.
 * Кнопка «Синхронізувати зараз» ({@code CalDavSyncController}) —
 * резерв на випадок, якщо треба негайно, не чекаючи наступного прогону.
 */
@Component
public class CalDavSyncScheduler {

    private final CalDavSyncService syncService;

    public CalDavSyncScheduler(CalDavSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelay = 300_000)
    public void sync() {
        syncService.syncCurrentAndNext();
    }
}
