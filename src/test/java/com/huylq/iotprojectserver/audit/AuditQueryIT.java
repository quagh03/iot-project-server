package com.huylq.iotprojectserver.audit;

import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.rules.RuleRepository;
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

import java.time.OffsetDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class AuditQueryIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired RuleRepository ruleRepo;
  @Autowired AuditLogRepository auditLogRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;

  @BeforeEach
  void seed() {
    auditLogRepo.deleteAll();
    ruleRepo.deleteAll();
    sensorRepo.deleteAll();
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void missing_time_window_is_unprocessable() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(get("/api/v1/audit-logs").header("Authorization", "Bearer " + admin))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void oversized_time_window_is_unprocessable() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(get("/api/v1/audit-logs")
            .param("from", "2000-01-01T00:00:00Z")
            .param("to", OffsetDateTime.now().plusDays(1).toString())
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void to_before_from_is_unprocessable() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(get("/api/v1/audit-logs")
            .param("from", "2026-06-25T10:00:00Z")
            .param("to", "2026-06-25T09:00:00Z")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void viewer_cannot_query_audit_logs() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/audit-logs")
            .param("from", "2026-06-01T00:00:00Z")
            .param("to", "2026-07-01T00:00:00Z")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
  }

  @Test
  void admin_can_query_and_filter_actions_from_earlier_phases() throws Exception {
    String admin = loginAs("admin");

    // Generates USER_LOGIN (already exercised by loginAs above, again here for a
    // second, distinguishable entry) + DEVICE_REGISTER + RULE_CREATE — exercising the
    // write side of three different modules (security/user, registry, rules) to prove
    // the query API surfaces audited actions from across the system, not just its own.
    mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_audit_1","category":"gateway","deviceType":"gateway","zone":"office_1"}"""))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/rules").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Audit coverage rule","condition":"office_1.smoke == true","action":"alert(SMOKE, CRITICAL)"}"""))
        .andExpect(status().isCreated());

    String from = OffsetDateTime.now().minusHours(1).toString();
    String to = OffsetDateTime.now().plusHours(1).toString();

    mvc.perform(get("/api/v1/audit-logs").param("from", from).param("to", to)
            .param("event", "user.login")
            .param("target", "admin")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.data[0].event").value("user.login"));

    mvc.perform(get("/api/v1/audit-logs").param("from", from).param("to", to)
            .param("event", "device.register")
            .param("target", "gw_audit_1")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].target").value("gw_audit_1"))
        .andExpect(jsonPath("$.data[0].actorType").value("USER"));

    mvc.perform(get("/api/v1/audit-logs").param("from", from).param("to", to)
            .param("event", "rule.create")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].detail.name").value("Audit coverage rule"));
  }

  @Test
  void filters_by_actorType() throws Exception {
    String admin = loginAs("admin");
    String from = OffsetDateTime.now().minusHours(1).toString();
    String to = OffsetDateTime.now().plusHours(1).toString();

    mvc.perform(get("/api/v1/audit-logs").param("from", from).param("to", to)
            .param("actorType", "USER")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

    mvc.perform(get("/api/v1/audit-logs").param("from", from).param("to", to)
            .param("actorType", "DEVICE")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void no_write_endpoints_exist() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(post("/api/v1/audit-logs").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().is4xxClientError());
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
