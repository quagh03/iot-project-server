package com.huylq.iotprojectserver.registry;

import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.security.Role;
import com.huylq.iotprojectserver.security.device.DeviceCredentialRepository;
import com.huylq.iotprojectserver.security.device.DeviceScopeRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class DeviceLifecycleIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceCredentialRepository credRepo;
  @Autowired DeviceScopeRepository scopeRepo;
  @Autowired CommandRepository commandRepo;

  private String admin;

  @BeforeEach
  void clean() throws Exception {
    commandRepo.deleteAll();
    credRepo.deleteAll();
    scopeRepo.deleteAll();
    sensorRepo.deleteAll();
    // Child devices (self-FK ON DELETE RESTRICT) before their gateways.
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
    admin = loginAs("admin");
    register("gw_1");
  }

  @Test
  void full_lifecycle_inactive_active_suspended_active_decommissioned() throws Exception {
    activate("gw_1").andExpect(status().isNoContent());
    assertStatus("gw_1", "ACTIVE");

    suspend("gw_1").andExpect(status().isNoContent());
    assertStatus("gw_1", "SUSPENDED");

    // SUSPENDED → ACTIVE is allowed
    activate("gw_1").andExpect(status().isNoContent());
    assertStatus("gw_1", "ACTIVE");

    decommission("gw_1").andExpect(status().isNoContent());
    assertStatus("gw_1", "DECOMMISSIONED");
  }

  @Test
  void activating_decommissioned_device_returns_409() throws Exception {
    activate("gw_1").andExpect(status().isNoContent());
    decommission("gw_1").andExpect(status().isNoContent());
    activate("gw_1")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type")
            .value("https://api.iot.example.com/errors/invalid-lifecycle-transition"));
  }

  @Test
  void suspending_inactive_device_returns_409() throws Exception {
    // gw_1 is INACTIVE on registration; only ACTIVE → SUSPENDED is legal.
    suspend("gw_1").andExpect(status().isConflict());
  }

  @Test
  void decommission_revokes_credentials_and_scopes() throws Exception {
    activate("gw_1").andExpect(status().isNoContent());
    // issue a credential + scopes first
    mvc.perform(post("/api/v1/devices/gw_1/credentials").header("Authorization", "Bearer " + admin))
        .andExpect(status().isCreated());
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/devices/gw_1/scopes")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"scopes":["telemetry:publish"]}"""))
        .andExpect(status().isOk());
    assertThat(credRepo.existsById("gw_1")).isTrue();
    assertThat(scopeRepo.findByDeviceId("gw_1")).isNotEmpty();

    decommission("gw_1").andExpect(status().isNoContent());

    assertThat(credRepo.existsById("gw_1")).isFalse();
    assertThat(scopeRepo.findByDeviceId("gw_1")).isEmpty();
    mvc.perform(get("/api/v1/devices/gw_1/credentials").header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void lifecycle_action_on_unknown_device_returns_404() throws Exception {
    activate("ghost").andExpect(status().isNotFound());
  }

  // ---- helpers ----

  private org.springframework.test.web.servlet.ResultActions activate(String id) throws Exception {
    return mvc.perform(post("/api/v1/devices/" + id + ":activate").header("Authorization", "Bearer " + admin));
  }

  private org.springframework.test.web.servlet.ResultActions suspend(String id) throws Exception {
    return mvc.perform(post("/api/v1/devices/" + id + ":suspend").header("Authorization", "Bearer " + admin));
  }

  private org.springframework.test.web.servlet.ResultActions decommission(String id) throws Exception {
    return mvc.perform(post("/api/v1/devices/" + id + ":decommission").header("Authorization", "Bearer " + admin));
  }

  private void assertStatus(String id, String status) {
    assertThat(deviceRepo.findById(id).orElseThrow().getStatus().name()).isEqualTo(status);
  }

  private void register(String id) throws Exception {
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"" + id + "\",\"category\":\"gateway\",\"deviceType\":\"gateway\",\"zone\":\"z\"}"))
        .andExpect(status().isCreated());
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
