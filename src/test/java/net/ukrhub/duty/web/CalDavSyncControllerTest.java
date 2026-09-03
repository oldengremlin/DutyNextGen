package net.ukrhub.duty.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CalDAV не налаштовано в цих тестах (порожній {@code duty.caldav.base-url}
 * за замовчуванням у application.yml) — перевіряємо лише доступ і що
 * ендпоінт не падає, а не сам факт синхронізації (це вже
 * {@code CalDavSyncServiceTest}, із фейковим сервером).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CalDavSyncControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanTriggerSyncNow() throws Exception {
        mockMvc.perform(post("/admin/caldav/sync-now").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"));
    }

    @Test
    @WithMockUser(roles = "EDITOR")
    void nonAdminCannotTriggerSync() throws Exception {
        mockMvc.perform(post("/admin/caldav/sync-now").with(csrf()))
                .andExpect(status().isForbidden());
    }
}
