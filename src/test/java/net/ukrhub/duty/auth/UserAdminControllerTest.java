package net.ukrhub.duty.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserAdminControllerTest {

    @TempDir
    static Path tempDir;

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void duty(DynamicPropertyRegistry registry) {
        registry.add("duty.data-dir", () -> tempDir.resolve("data").toString());
        registry.add("duty.config-dir", () -> tempDir.resolve("config").toString());
    }

    private Path usersFile() {
        return tempDir.resolve("config").resolve("users.txt");
    }

    @Test
    @WithMockUser(roles = "EDITOR")
    void nonAdminCannotOpenUserAdmin() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanCreateAndListUsers() throws Exception {
        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .param("username", "novyi")
                        .param("password", "secret123")
                        .param("confirm", "secret123")
                        .param("role", "VIEWER"))
                .andExpect(status().is3xxRedirection());

        UserStore.StoredUser stored = UserStore.readUsers(usersFile()).get("novyi");
        assertThat(stored).isNotNull();
        assertThat(stored.role()).isEqualTo(Role.VIEWER);

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("novyi")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void creatingDuplicateUserIsConflict() throws Exception {
        UserStore.writeUser(usersFile(), "existing", "hash", Role.VIEWER);

        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .param("username", "existing")
                        .param("password", "secret123")
                        .param("confirm", "secret123")
                        .param("role", "VIEWER"))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanChangeRoleAndResetPassword() throws Exception {
        UserStore.writeUser(usersFile(), "someone", new BCryptPasswordEncoder().encode("old"), Role.VIEWER);

        mockMvc.perform(post("/admin/users/someone/role")
                        .with(csrf())
                        .param("role", "EDITOR"))
                .andExpect(status().is3xxRedirection());
        assertThat(UserStore.readUsers(usersFile()).get("someone").role()).isEqualTo(Role.EDITOR);

        String oldHash = UserStore.readUsers(usersFile()).get("someone").passwordHash();
        mockMvc.perform(post("/admin/users/someone/password")
                        .with(csrf())
                        .param("password", "new-secret")
                        .param("confirm", "new-secret"))
                .andExpect(status().is3xxRedirection());
        assertThat(UserStore.readUsers(usersFile()).get("someone").passwordHash()).isNotEqualTo(oldHash);
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void adminCannotDeleteSelf() throws Exception {
        UserStore.writeUser(usersFile(), "admin1", "hash", Role.ADMIN);

        mockMvc.perform(post("/admin/users/admin1/delete").with(csrf()))
                .andExpect(status().isBadRequest());

        assertThat(UserStore.readUsers(usersFile())).containsKey("admin1");
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void adminCanDeleteOtherUser() throws Exception {
        UserStore.writeUser(usersFile(), "admin1", "hash", Role.ADMIN);
        UserStore.writeUser(usersFile(), "toRemove", "hash2", Role.VIEWER);

        mockMvc.perform(post("/admin/users/toRemove/delete").with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(UserStore.readUsers(usersFile())).doesNotContainKey("toRemove");
    }
}
