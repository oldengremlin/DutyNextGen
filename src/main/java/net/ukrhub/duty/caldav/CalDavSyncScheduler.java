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
        syncService.syncRecentMonths();
    }
}
