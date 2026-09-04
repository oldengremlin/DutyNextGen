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

import net.ukrhub.duty.caldav.CalDavSyncService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Ручний запуск CalDAV-синку — лише ADMIN (обмежено {@code /admin/**}
 * у {@code SecurityConfig}). Той самий метод, що й фоновий
 * {@code CalDavSyncScheduler} — просто негайно, а не за розкладом.
 */
@Controller
public class CalDavSyncController {

    private final CalDavSyncService syncService;

    public CalDavSyncController(CalDavSyncService syncService) {
        this.syncService = syncService;
    }

    @PostMapping("/admin/caldav/sync-now")
    public String syncNow(RedirectAttributes redirectAttributes) {
        syncService.syncRecentMonths();
        redirectAttributes.addFlashAttribute("caldavSyncDone", true);
        return "redirect:/admin/users";
    }
}
