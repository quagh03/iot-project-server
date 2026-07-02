package com.huylq.iotprojectserver.security.detection;

import com.huylq.iotprojectserver.alert.AlertRepository;
import com.huylq.iotprojectserver.security.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the detection wiring end to end through the real Spring context — not just
 * {@code SecurityDetectionServiceTest}'s unit-level threshold logic, but that a real
 * failed-login HTTP flow actually reaches it and a real {@code Alert} row lands in the DB.
 */
@PostgresIntegrationTest
class DetectionEndToEndIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired AlertRepository alertRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;

  @BeforeEach
  void seed() {
    alertRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    support.createUser("carol", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void repeated_failed_logins_raise_an_auth_failure_burst_alert() throws Exception {
    // Default threshold is 5 (application.yaml) — the 5th failure must cross it.
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/v1/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(Map.of("username", "carol", "password", "wrong-password"))))
          .andExpect(status().isUnauthorized());
    }

    assertThat(alertRepo.findAll()).anySatisfy(a -> {
      assertThat(a.getType()).isEqualTo("AUTH_FAILURE_BURST");
      assertThat(a.getSeverity().name()).isEqualTo("WARNING");
    });
  }

  @Test
  void a_single_failed_login_does_not_raise_an_alert() throws Exception {
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", "carol", "password", "wrong-password"))))
        .andExpect(status().isUnauthorized());

    assertThat(alertRepo.findAll()).noneSatisfy(a -> assertThat(a.getType()).isEqualTo("AUTH_FAILURE_BURST"));
  }
}
