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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class AuthFlowIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SecurityTestSupport support;
    @Autowired UserRepository userRepo;
    @Autowired RefreshTokenRepository refreshRepo;

    @BeforeEach
    void clean() {
        refreshRepo.deleteAll();
        userRepo.deleteAll();
        support.createUser("ada", "correct-horse-battery-staple", Role.OPERATOR);
    }

    @Test
    void login_returns_access_and_refresh_tokens() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ada","password":"correct-horse-battery-staple"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> parsed = support.parseJson(body);
        assertThat(parsed.get("accessToken")).asString().isNotBlank();
        assertThat(parsed.get("refreshToken")).asString().isNotBlank();
    }

    @Test
    void bad_credentials_return_401_problem_detail() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ada","password":"wrong"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.iot.example.com/errors/unauthenticated"));
    }

    @Test
    void refresh_rotates_and_old_token_reuse_returns_token_revoked() throws Exception {
        Map<String, Object> first = login();
        String oldRefresh = (String) first.get("refreshToken");

        // First refresh succeeds, returns new pair
        String refreshBody = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> second = support.parseJson(refreshBody);
        assertThat(second.get("refreshToken")).isNotEqualTo(oldRefresh);

        // Reusing the old refresh token must be detected as compromise.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://api.iot.example.com/errors/token-revoked"));

        // ...and the new refresh, having been cascade-revoked, must also be rejected.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", second.get("refreshToken")))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_revokes_refresh_token() throws Exception {
        Map<String, Object> first = login();
        String refresh = (String) first.get("refreshToken");

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
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
