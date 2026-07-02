package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.security.Role;
import com.huylq.iotprojectserver.security.device.DeviceCredential;
import com.huylq.iotprojectserver.security.device.DeviceCredentialRepository;
import com.huylq.iotprojectserver.security.device.DeviceScope;
import com.huylq.iotprojectserver.security.device.DeviceScopeRepository;
import com.huylq.iotprojectserver.security.user.RefreshTokenRepository;
import com.huylq.iotprojectserver.security.user.UserRepository;
import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import com.huylq.iotprojectserver.support.SecurityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class CommandIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceCredentialRepository credRepo;
  @Autowired DeviceScopeRepository scopeRepo;
  @Autowired CommandRepository commandRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired PasswordEncoder encoder;

  @MockitoBean private SafetyInterlockCheck safetyInterlockCheck;

  private String admin;

  @BeforeEach
  void seed() throws Exception {
    commandRepo.deleteAll();
    credRepo.deleteAll();
    scopeRepo.deleteAll();
    sensorRepo.deleteAll();
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    when(safetyInterlockCheck.violatesActiveSafety(anyString(), anyString(), any())).thenReturn(false);

    support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
    support.createUser("super", "s3cret-string-32-bytes-long-now", Role.SUPER_ADMIN);
    support.createUser("operator", "s3cret-string-32-bytes-long-now", Role.OPERATOR);
    support.createUser("technician", "s3cret-string-32-bytes-long-now", Role.TECHNICIAN);
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
    admin = loginAs("admin");

    registerAndActivate("light_1", "actuator", "light", "office_1");
    registerAndActivate("exhst_1", "actuator", "exhst_fan", "office_1");
    registerAndActivate("gw_1", "gateway", "gateway", "office_1");

    credRepo.save(DeviceCredential.builder().device(deviceRepo.findById("gw_1").orElseThrow())
        .clientId("cli_gw_1").clientSecretHash(encoder.encode("device-secret")).build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_1").scope("telemetry:publish").build());
  }

  @Test
  void operator_can_issue_command_and_poll_status() throws Exception {
    String operator = loginAs("operator");
    String key = UUID.randomUUID().toString();
    String body = mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isAccepted())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andReturn().getResponse().getContentAsString();
    String commandId = (String) support.parseJson(body).get("commandId");

    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/commands/" + commandId).header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.targetId").value("light_1"));

    mvc.perform(get("/api/v1/devices/light_1/actuator-state").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.desiredState").value("ON"))
        .andExpect(jsonPath("$.inFlight").value(true));
  }

  @Test
  void idempotent_replay_returns_the_same_command() throws Exception {
    String operator = loginAs("operator");
    String key = UUID.randomUUID().toString();
    String requestBody = """
        {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}""";

    String first = mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(requestBody))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();
    String replay = mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(requestBody))
        .andExpect(status().isAccepted())
        .andReturn().getResponse().getContentAsString();

    assertThat(support.parseJson(replay).get("commandId")).isEqualTo(support.parseJson(first).get("commandId"));
    assertThat(commandRepo.count()).isEqualTo(1);
  }

  @Test
  void idempotency_key_reused_with_different_body_is_conflict() throws Exception {
    String operator = loginAs("operator");
    String key = UUID.randomUUID().toString();
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isAccepted());

    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"OFF"}}"""))
        .andExpect(status().isConflict());
  }

  @Test
  void missing_idempotency_key_is_bad_request() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isBadRequest());
  }

  @Test
  void viewer_cannot_issue_command() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + viewer).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void device_token_cannot_issue_command() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + device).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void technician_cannot_command_a_safety_actuator() throws Exception {
    String technician = loginAs("technician");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + technician).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"exhst_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void technician_can_command_a_routine_actuator() throws Exception {
    String technician = loginAs("technician");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + technician).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isAccepted());
  }

  @Test
  void unknown_target_is_unprocessable() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"ghost","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void non_actuator_target_is_unprocessable() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"gw_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void unknown_parameter_is_unprocessable() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON","warp_speed":9}}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void active_safety_hold_returns_409_and_super_admin_can_override() throws Exception {
    when(safetyInterlockCheck.violatesActiveSafety(org.mockito.ArgumentMatchers.eq("exhst_1"), anyString(), any()))
        .thenReturn(true);

    String admin = loginAs("admin");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + admin).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"exhst_1","type":"actuator","action":"SET","parameters":{"status":"OFF"}}"""))
        .andExpect(status().isConflict());

    String superAdmin = loginAs("super");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + superAdmin).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"exhst_1","type":"actuator","action":"SET","parameters":{"status":"OFF"},
                 "override":true,"overrideReason":"fire drill confirmed safe"}"""))
        .andExpect(status().isAccepted());
  }

  @Test
  void override_by_admin_is_forbidden() throws Exception {
    when(safetyInterlockCheck.violatesActiveSafety(anyString(), anyString(), any())).thenReturn(true);
    String admin = loginAs("admin");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + admin).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"exhst_1","type":"actuator","action":"SET","parameters":{"status":"OFF"},
                 "override":true,"overrideReason":"trying anyway"}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void list_commands_filters_by_targetId_and_status() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isAccepted());

    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/commands").param("targetId", "light_1").param("status", "PENDING")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].targetId").value("light_1"));

    mvc.perform(get("/api/v1/commands").param("targetId", "exhst_1")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void actuator_state_list_supports_zone_and_drifted_filters() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/commands")
            .header("Authorization", "Bearer " + operator).header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"targetId":"light_1","type":"actuator","action":"SET","parameters":{"status":"ON"}}"""))
        .andExpect(status().isAccepted());

    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/actuator-state").param("zone", "office_1")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1));

    mvc.perform(get("/api/v1/actuator-state").param("drifted", "true")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].deviceId").value("light_1"));
  }

  @Test
  void device_actuator_state_returns_404_for_non_actuator() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/devices/gw_1/actuator-state").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isNotFound());
  }

  @Test
  void device_actuator_state_returns_404_when_no_command_issued_yet() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/devices/exhst_1/actuator-state").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isNotFound());
  }

  // ---- helpers ----

  private void registerAndActivate(String deviceId, String category, String deviceType, String zone) throws Exception {
    mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"" + deviceId + "\",\"category\":\"" + category
                + "\",\"deviceType\":\"" + deviceType + "\",\"zone\":\"" + zone + "\"}"))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/devices/" + deviceId + ":activate").header("Authorization", "Bearer " + admin))
        .andExpect(status().isNoContent());
  }

  private String deviceToken() throws Exception {
    String body = mvc.perform(post("/api/v1/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "client_credentials")
            .param("client_id", "cli_gw_1")
            .param("client_secret", "device-secret"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return (String) support.parseJson(body).get("access_token");
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
