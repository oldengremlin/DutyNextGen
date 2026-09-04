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

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanLinkAndUnlinkUserToEngineer() throws Exception {
        UserStore.writeUser(usersFile(), "chergovyi", "hash", Role.VIEWER);

        mockMvc.perform(post("/admin/users/chergovyi/link")
                        .with(csrf())
                        .param("linkedEngineer", "Іванов І."))
                .andExpect(status().is3xxRedirection());
        assertThat(UserStore.readUsers(usersFile()).get("chergovyi").linkedEngineer()).isEqualTo("Іванов І.");

        mockMvc.perform(post("/admin/users/chergovyi/link")
                        .with(csrf())
                        .param("linkedEngineer", ""))
                .andExpect(status().is3xxRedirection());
        assertThat(UserStore.readUsers(usersFile()).get("chergovyi").linkedEngineer()).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void changingRoleOrPasswordPreservesExistingLink() throws Exception {
        UserStore.writeUser(usersFile(), "chergovyi", "hash", Role.VIEWER, "Іванов І.");

        mockMvc.perform(post("/admin/users/chergovyi/role")
                        .with(csrf())
                        .param("role", "EDITOR"))
                .andExpect(status().is3xxRedirection());

        assertThat(UserStore.readUsers(usersFile()).get("chergovyi").linkedEngineer()).isEqualTo("Іванов І.");
    }

    @Test
    @WithMockUser(username = "acting-admin", roles = "ADMIN")
    void otherAdminCanBeDowngradedWhenNotLast() throws Exception {
        UserStore.writeUser(usersFile(), "acting-admin", "hash", Role.ADMIN);
        UserStore.writeUser(usersFile(), "another-admin", "hash2", Role.ADMIN);

        mockMvc.perform(post("/admin/users/another-admin/role")
                        .with(csrf())
                        .param("role", "VIEWER"))
                .andExpect(status().is3xxRedirection());
        assertThat(UserStore.readUsers(usersFile()).get("another-admin").role()).isEqualTo(Role.VIEWER);
    }

    /**
     * Регресія на реальний випадок: адміністратор сам собі поніс роль
     * через /admin/users/{себе}/role, маючи ще одного адміна в системі —
     * "останній адміністратор" тоді не спрацьовує (адмінів двоє), але
     * саме ця сесія однаково втрачає права. Самозаборона має діяти
     * незалежно від того, скільки ще є адміністраторів.
     */
    @Test
    @WithMockUser(username = "self-admin", roles = "ADMIN")
    void adminCannotChangeOwnRoleEvenWithAnotherAdminPresent() throws Exception {
        UserStore.writeUser(usersFile(), "self-admin", "hash", Role.ADMIN);
        UserStore.writeUser(usersFile(), "other-admin", "hash2", Role.ADMIN);

        mockMvc.perform(post("/admin/users/self-admin/role")
                        .with(csrf())
                        .param("role", "VIEWER"))
                .andExpect(status().isBadRequest());

        assertThat(UserStore.readUsers(usersFile()).get("self-admin").role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @WithMockUser(username = "solo-admin", roles = "ADMIN")
    void adminCannotChangeOwnRoleWhenSoleAdmin() throws Exception {
        UserStore.writeUser(usersFile(), "solo-admin", "hash", Role.ADMIN);

        mockMvc.perform(post("/admin/users/solo-admin/role")
                        .with(csrf())
                        .param("role", "VIEWER"))
                .andExpect(status().isBadRequest());

        assertThat(UserStore.readUsers(usersFile()).get("solo-admin").role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createCanSetLinkedEngineer() throws Exception {
        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .param("username", "novyi2")
                        .param("password", "secret123")
                        .param("confirm", "secret123")
                        .param("role", "VIEWER")
                        .param("linkedEngineer", "Петров П."))
                .andExpect(status().is3xxRedirection());

        assertThat(UserStore.readUsers(usersFile()).get("novyi2").linkedEngineer()).isEqualTo("Петров П.");
    }

    /**
     * До появи політики паролів перевірялось лише "не порожній" — тобто
     * пароль з однієї літери проходив. Basic-автентифікація не має ні
     * обмеження спроб, ні затримки, тож такий пароль підбирається за
     * секунди.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void tooShortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .param("username", "korotkyi")
                        .param("password", "a")
                        .param("confirm", "a")
                        .param("role", "VIEWER"))
                .andExpect(status().isBadRequest());

        assertThat(UserStore.readUsers(usersFile()).get("korotkyi")).isNull();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void tooShortPasswordIsRejectedOnResetToo() throws Exception {
        UserStore.writeUser(usersFile(), "reset-target", "hash", Role.VIEWER);

        mockMvc.perform(post("/admin/users/reset-target/password")
                        .with(csrf())
                        .param("password", "a")
                        .param("confirm", "a"))
                .andExpect(status().isBadRequest());

        assertThat(UserStore.readUsers(usersFile()).get("reset-target").passwordHash()).isEqualTo("hash");
    }

    /**
     * Ім'я з роздільником полів розпалось би при наступному читанні
     * users.txt на чужі поля (хеш + роль) — тобто дозволило б підняти собі
     * права через форму створення користувача. Має бути зрозуміла відмова
     * 400, а не 500 і не мовчазний запис.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void usernameWithFieldSeparatorIsRejected() throws Exception {
        mockMvc.perform(post("/admin/users/create")
                        .with(csrf())
                        .param("username", "zlyi:$2a$10$hash:ADMIN")
                        .param("password", "secret123")
                        .param("confirm", "secret123")
                        .param("role", "VIEWER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void linkedEngineerWithFieldSeparatorIsRejected() throws Exception {
        UserStore.writeUser(usersFile(), "link-target", "hash", Role.VIEWER);

        mockMvc.perform(post("/admin/users/link-target/link")
                        .with(csrf())
                        .param("linkedEngineer", "Іванов І.:hash:ADMIN"))
                .andExpect(status().isBadRequest());

        assertThat(UserStore.readUsers(usersFile()).get("link-target").linkedEngineer()).isNull();
    }
}
