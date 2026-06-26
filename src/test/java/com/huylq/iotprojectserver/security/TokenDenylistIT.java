package com.huylq.iotprojectserver.security;

import com.huylq.iotprojectserver.security.Role;

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
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class TokenDenylistIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SecurityTestSupport support;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshRepo;

    @BeforeEach
    void clean() {
        refreshRepo.deleteAll();
        userRepo.deleteAll();
        support.createUser("ada", "correct-horse-battery-staple", Role.ADMIN);
    }

    @Test
    void after_logout_access_token_cannot_hit_protected_endpoint() throws Exception {
        Map<String, Object> tokens = login();
        String access = (String) tokens.get("accessToken");
        String refresh = (String) tokens.get("refreshToken");

        // Sanity: the access token works before logout.
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        // Logout with both tokens — must add the access JTI to the denylist.
        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());

        // Same access token must now be rejected.
        mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void after_logout_refresh_token_cannot_refresh() throws Exception {
        Map<String, Object> tokens = login();
        String refresh = (String) tokens.get("refreshToken");

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());

        // Refresh denied — denylist short-circuits before DB read.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://api.iot.example.com/errors/token-revoked"));
    }

    @Test
    void rotated_refresh_token_is_in_denylist_after_refresh() throws Exception {
        Map<String, Object> tokens = login();
        String oldRefresh = (String) tokens.get("refreshToken");

        // Rotate once — the old refresh should land in the denylist.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk());

        // Replaying the old refresh hits the denylist short-circuit, returns token-revoked.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://api.iot.example.com/errors/token-revoked"));
    }

    private Map<String, Object> login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ada","password":"correct-horse-battery-staple"}"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return support.parseJson(body);
    }
}
