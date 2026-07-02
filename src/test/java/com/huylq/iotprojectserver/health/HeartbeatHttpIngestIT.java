package com.huylq.iotprojectserver.health;

import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class HeartbeatHttpIngestIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceCredentialRepository credRepo;
  @Autowired DeviceScopeRepository scopeRepo;
  @Autowired DeviceHealthRepository healthRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired PasswordEncoder encoder;

  @BeforeEach
  void seed() {
    healthRepo.deleteAll();
    credRepo.deleteAll();
    scopeRepo.deleteAll();
    sensorRepo.deleteAll();
    // Child (sensor) devices reference their gateway via a self-FK (ON DELETE RESTRICT),
    // so delete devices that have a parent before the gateways themselves.
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();

    Device gateway = deviceRepo.save(Device.builder()
        .deviceId("gw_1").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());
    Device otherGateway = deviceRepo.save(Device.builder()
        .deviceId("gw_2").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_2").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());
    sensorRepo.save(Sensor.builder().sensorId("s_temp_1").gateway(gateway).type("temp").zone("office_1").build());

    credRepo.save(DeviceCredential.builder().device(gateway).clientId("cli_gw_1")
        .clientSecretHash(encoder.encode("device-secret")).build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_1").scope("heartbeat:publish").build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_1").scope("telemetry:publish").build());

    credRepo.save(DeviceCredential.builder().device(otherGateway).clientId("cli_gw_2")
        .clientSecretHash(encoder.encode("device-secret")).build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_2").scope("heartbeat:publish").build());

    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void valid_heartbeat_is_accepted_and_upserts_device_health() throws Exception {
    String device = deviceToken("cli_gw_1");
    mvc.perform(post("/api/v1/heartbeat")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_1","memoryUsagePct":43,"cpuUsagePct":21,"wifiRssi":-58}"""))
        .andExpect(status().isAccepted());

    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/devices/gw_1/health").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deviceId").value("gw_1"))
        .andExpect(jsonPath("$.connectionStatus").value("ONLINE"))
        .andExpect(jsonPath("$.memoryUsagePct").value(43))
        .andExpect(jsonPath("$.cpuUsagePct").value(21))
        .andExpect(jsonPath("$.wifiRssi").value(-58))
        .andExpect(jsonPath("$.lastSeen").exists());
  }

  @Test
  void deviceId_not_matching_token_identity_is_forbidden() throws Exception {
    String device = deviceToken("cli_gw_1");
    mvc.perform(post("/api/v1/heartbeat")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_2"}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void user_token_cannot_send_heartbeat() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(post("/api/v1/heartbeat")
            .header("Authorization", "Bearer " + viewer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_1"}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void get_health_returns_404_when_device_has_no_heartbeat_yet() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/devices/gw_2/health").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isNotFound());
  }

  @Test
  void telemetry_ingest_also_marks_the_gateway_online() throws Exception {
    String device = deviceToken("cli_gw_1");
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_temp_1","sensorType":"temp","valueNum":22.4,"unit":"C","ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isAccepted());

    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/devices/gw_1/health").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.connectionStatus").value("ONLINE"))
        .andExpect(jsonPath("$.lastSeen").exists())
        .andExpect(jsonPath("$.memoryUsagePct").doesNotExist());
  }

  private String deviceToken(String clientId) throws Exception {
    String body = mvc.perform(post("/api/v1/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "client_credentials")
            .param("client_id", clientId)
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
