package com.huylq.iotprojectserver.alert;

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
class AlertIT {

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
    support.createUser("operator", "s3cret-string-32-bytes-long-now", Role.OPERATOR);
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  private Long seedAlert(Alert.Status status) {
    Alert alert = alertRepo.save(Alert.builder()
        .type("SMOKE").severity(Alert.Severity.CRITICAL).zone("office_1")
        .message("Smoke detected").status(status).build());
    return alert.getId();
  }

  @Test
  void operator_can_acknowledge_then_resolve_an_open_alert() throws Exception {
    Long id = seedAlert(Alert.Status.OPEN);
    String operator = loginAs("operator");

    mvc.perform(post("/api/v1/alerts/" + id + ":acknowledge").header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACK"));

    mvc.perform(post("/api/v1/alerts/" + id + ":resolve").header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"));
  }

  @Test
  void operator_can_resolve_directly_from_open_without_acknowledging() throws Exception {
    Long id = seedAlert(Alert.Status.OPEN);
    String operator = loginAs("operator");

    mvc.perform(post("/api/v1/alerts/" + id + ":resolve").header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RESOLVED"));
  }

  @Test
  void acknowledging_an_already_resolved_alert_is_conflict() throws Exception {
    Long id = seedAlert(Alert.Status.RESOLVED);
    String operator = loginAs("operator");

    mvc.perform(post("/api/v1/alerts/" + id + ":acknowledge").header("Authorization", "Bearer " + operator))
        .andExpect(status().isConflict());
  }

  @Test
  void resolving_an_already_resolved_alert_is_conflict() throws Exception {
    Long id = seedAlert(Alert.Status.RESOLVED);
    String operator = loginAs("operator");

    mvc.perform(post("/api/v1/alerts/" + id + ":resolve").header("Authorization", "Bearer " + operator))
        .andExpect(status().isConflict());
  }

  @Test
  void unknown_alert_id_is_not_found() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/alerts/999999:acknowledge").header("Authorization", "Bearer " + operator))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/alerts/999999").header("Authorization", "Bearer " + operator))
        .andExpect(status().isNotFound());
  }

  @Test
  void viewer_cannot_acknowledge_or_resolve() throws Exception {
    Long id = seedAlert(Alert.Status.OPEN);
    String viewer = loginAs("viewer");

    mvc.perform(post("/api/v1/alerts/" + id + ":acknowledge").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/alerts/" + id + ":resolve").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewer_can_read_and_list_alerts() throws Exception {
    Long id = seedAlert(Alert.Status.OPEN);
    String viewer = loginAs("viewer");

    mvc.perform(get("/api/v1/alerts/" + id).header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SMOKE"))
        .andExpect(jsonPath("$.severity").value("CRITICAL"));

    mvc.perform(get("/api/v1/alerts").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
  }

  @Test
  void list_filters_by_status_zone_and_severity() throws Exception {
    seedAlert(Alert.Status.OPEN);
    alertRepo.save(Alert.builder().type("HEAT").severity(Alert.Severity.WARNING).zone("office_2")
        .status(Alert.Status.RESOLVED).build());
    String viewer = loginAs("viewer");

    mvc.perform(get("/api/v1/alerts").param("status", "OPEN").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].type").value("SMOKE"));

    mvc.perform(get("/api/v1/alerts").param("zone", "office_2").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].type").value("HEAT"));

    mvc.perform(get("/api/v1/alerts").param("severity", "CRITICAL").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));
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
