package com.huylq.iotprojectserver.common;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class ObservabilityIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;

  @BeforeEach
  void seed() {
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void liveness_and_readiness_probes_are_public() throws Exception {
    mvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void prometheus_endpoint_requires_admin() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isUnauthorized());

    String viewer = loginAs("viewer");
    mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());

    String admin = loginAs("admin");
    mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
  }

  @Test
  void custom_metrics_are_exposed_to_admin() throws Exception {
    String admin = loginAs("admin");
    String body = mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(body)
        .contains("iot_rules_queue_depth")
        .contains("iot_partition_size_bytes")
        .contains("iot_partition_missing");
  }

  private String loginAs(String username) throws Exception {
    String body = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", username,
                "password", "s3cret-string-32-bytes-long-now"))))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return (String) support.parseJson(body).get("accessToken");
  }
}
