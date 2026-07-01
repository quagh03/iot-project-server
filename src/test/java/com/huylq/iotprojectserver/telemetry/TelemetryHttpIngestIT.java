package com.huylq.iotprojectserver.telemetry;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class TelemetryHttpIngestIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceCredentialRepository credRepo;
  @Autowired DeviceScopeRepository scopeRepo;
  @Autowired TelemetryRepository telemetryRepo;
  @Autowired SensorLatestRepository sensorLatestRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired PasswordEncoder encoder;

  @BeforeEach
  void seed() {
    telemetryRepo.deleteAll();
    sensorLatestRepo.deleteAll();
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
    sensorRepo.save(Sensor.builder().sensorId("s_smoke_1").gateway(gateway).type("smoke").zone("office_1").build());
    sensorRepo.save(Sensor.builder().sensorId("s_temp_2").gateway(otherGateway).type("temp").zone("office_2").build());

    credRepo.save(DeviceCredential.builder().device(gateway).clientId("cli_gw_1")
        .clientSecretHash(encoder.encode("device-secret")).build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_1").scope("telemetry:publish").build());

    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void valid_batch_is_accepted_and_lands_in_telemetry_and_sensor_latest() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_temp_1","sensorType":"temp","valueNum":22.4,"unit":"C","ts":"2026-06-25T10:30:00Z"},
                  {"sensorId":"s_smoke_1","sensorType":"smoke","valueBool":false,"ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isAccepted());

    assertThat(telemetryRepo.count()).isEqualTo(2);
    assertThat(sensorLatestRepo.findById("s_temp_1")).isPresent();
    assertThat(sensorLatestRepo.findById("s_temp_1").get().getValueNum()).isEqualTo(22.4);
    assertThat(sensorLatestRepo.findById("s_smoke_1").get().getValueBool()).isFalse();

    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/current-state").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void gatewayId_not_matching_token_identity_is_forbidden() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_2","zone":"office_2","readings":[
                  {"sensorId":"s_temp_2","sensorType":"temp","valueNum":22.4,"unit":"C","ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isForbidden());

    assertThat(telemetryRepo.count()).isZero();
  }

  @Test
  void unknown_sensor_id_returns_422() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_ghost","sensorType":"temp","valueNum":22.4,"unit":"C","ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void sensor_type_mismatch_returns_422() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_temp_1","sensorType":"smoke","valueBool":true,"ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void both_valueNum_and_valueBool_returns_422() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_temp_1","sensorType":"temp","valueNum":22.4,"valueBool":true,"ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void empty_readings_returns_422() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[]}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void user_token_cannot_ingest_telemetry() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_temp_1","sensorType":"temp","valueNum":22.4,"unit":"C","ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isForbidden());
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
