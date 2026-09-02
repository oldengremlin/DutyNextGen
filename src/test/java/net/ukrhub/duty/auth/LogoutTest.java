package net.ukrhub.duty.auth;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP Basic не має поняття "вийти" на рівні протоколу (браузер сам кешує
 * й повторно надсилає облікові дані) — {@code /logout} лише інвалідує
 * сесію й повертає 401 замість редиректу на неіснуючу сторінку логіну
 * (SecurityConfig.onLogoutSuccess).
 */
@SpringBootTest
@AutoConfigureMockMvc
class LogoutTest {

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
    @WithMockUser(username = "noc", roles = "VIEWER")
    void logoutReturnsUnauthorizedWithExplanation() throws Exception {
        mockMvc.perform(post("/logout").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Сесію застосунку завершено")));
    }

    @Test
    void logoutWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(post("/logout"))
                .andExpect(status().isForbidden());
    }
}
