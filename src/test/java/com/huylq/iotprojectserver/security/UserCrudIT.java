package com.huylq.iotprojectserver.security;

import com.huylq.iotprojectserver.security.Role;

import tools.jackson.databind.ObjectMapper;
import com.huylq.iotprojectserver.security.user.User;
import com.huylq.iotprojectserver.security.user.RefreshTokenRepository;
import com.huylq.iotprojectserver.security.user.UserRepository;
import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import com.huylq.iotprojectserver.support.SecurityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class UserCrudIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SecurityTestSupport support;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshRepo;

    @BeforeEach
    void clean() {
        refreshRepo.deleteAll();
        userRepo.deleteAll();
        support.createUser("super", "s3cret-string-32-bytes-long-now", Role.SUPER_ADMIN);
        support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
        support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
    }

    @Test
    void admin_can_create_operator_but_cannot_grant_admin() throws Exception {
        String adminTok = loginAs("admin");

        mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-op","password":"pw-pw-pw-pw","role":"OPERATOR"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        // ADMIN trying to grant ADMIN → 403 (insufficient authority)
        mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-admin","password":"pw-pw-pw-pw","role":"ADMIN"}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://api.iot.example.com/errors/forbidden"));
    }

    @Test
    void super_admin_can_grant_admin() throws Exception {
        String superTok = loginAs("super");
        mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + superTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new-admin","password":"pw-pw-pw-pw","role":"ADMIN"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void duplicate_username_returns_409() throws Exception {
        String adminTok = loginAs("admin");
        mvc.perform(post("/api/v1/users")
                        .header("Authorization", "Bearer " + adminTok)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"viewer","password":"pw-pw-pw-pw","role":"VIEWER"}"""))
                .andExpect(status().isConflict());
    }

    @Test
    void viewer_calling_admin_endpoint_gets_403() throws Exception {
        String viewerTok = loginAs("viewer");
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + viewerTok))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticated_get_users_returns_401() throws Exception {
        mvc.perform(get("/api/v1/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void soft_delete_sets_disabled_and_revokes_refresh_tokens() throws Exception {
        String adminTok = loginAs("admin");
        String viewerId = userRepo.findByUsername("viewer").orElseThrow().getId().toString();
        // Generate a refresh token for viewer
        loginAs("viewer");

        mvc.perform(delete("/api/v1/users/" + viewerId)
                        .header("Authorization", "Bearer " + adminTok))
                .andExpect(status().isNoContent());

        // Viewer is now DISABLED
        User reloaded = userRepo.findByUsername("viewer").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getStatus()).isEqualTo(User.Status.DISABLED);
        org.assertj.core.api.Assertions.assertThat(
                refreshRepo.findAll().stream()
                        .filter(r -> r.getUser().getId().toString().equals(viewerId))
                        .filter(r -> !r.getRevoked())
                        .toList()
        ).isEmpty();
    }

    private String loginAs(String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", username,
                                "password", "s3cret-string-32-bytes-long-now"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (String) support.parseJson(body).get("accessToken");
    }
}
