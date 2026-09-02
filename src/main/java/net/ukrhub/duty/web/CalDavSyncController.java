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
        syncService.syncCurrentAndNext();
        redirectAttributes.addFlashAttribute("caldavSyncDone", true);
        return "redirect:/admin/users";
    }
}
