package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.security.device.DeviceCredential;
import com.huylq.iotprojectserver.security.device.DeviceCredentialRepository;
import com.huylq.iotprojectserver.security.device.DeviceScope;
import com.huylq.iotprojectserver.security.device.DeviceScopeRepository;
import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import com.huylq.iotprojectserver.support.SecurityTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the {@code RateLimitFilter} fix keying {@code TELEMETRY} by device identity
 * (API §1) rather than client IP — a dedicated property override since the shared
 * {@code test} profile disables rate limiting globally.
 */
@PostgresIntegrationTest
@TestPropertySource(properties = {
    "iot.rate-limit.enabled=true",
    "iot.rate-limit.telemetry-per-minute=2"
})
class TelemetryRateLimitIT {

  @Autowired MockMvc mvc;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceCredentialRepository credRepo;
  @Autowired DeviceScopeRepository scopeRepo;
  @Autowired PasswordEncoder encoder;
  @Autowired CommandRepository commandRepo;

  @BeforeEach
  void seed() {
    commandRepo.deleteAll();
    credRepo.deleteAll();
    scopeRepo.deleteAll();
    sensorRepo.deleteAll();
    // Child (sensor) devices reference their gateway via a self-FK (ON DELETE RESTRICT),
    // so delete devices that have a parent before the gateways themselves.
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();

    Device gateway = deviceRepo.save(Device.builder()
        .deviceId("gw_1").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());
    sensorRepo.save(Sensor.builder().sensorId("s_temp_1").gateway(gateway).type("temp").zone("office_1").build());

    credRepo.save(DeviceCredential.builder().device(gateway).clientId("cli_gw_1")
        .clientSecretHash(encoder.encode("device-secret")).build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_1").scope("telemetry:publish").build());
  }

  @Test
  void third_request_within_a_minute_from_the_same_device_is_429() throws Exception {
    String device = deviceToken();
    String body = """
        {"gatewayId":"gw_1","zone":"office_1","readings":[
          {"sensorId":"s_temp_1","sensorType":"temp","valueNum":22.4,"unit":"C","ts":"2026-06-25T10:30:00Z"}
        ]}""";

    for (int i = 0; i < 2; i++) {
      mvc.perform(post("/api/v1/telemetry")
              .header("Authorization", "Bearer " + device)
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isAccepted());
    }
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isTooManyRequests());
  }

  private String deviceToken() throws Exception {
    String responseBody = mvc.perform(post("/api/v1/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "client_credentials")
            .param("client_id", "cli_gw_1")
            .param("client_secret", "device-secret"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return (String) support.parseJson(responseBody).get("access_token");
  }
}
