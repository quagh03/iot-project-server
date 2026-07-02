package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertRepository;
import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
import com.huylq.iotprojectserver.registry.SensorRepository;
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

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Phase 7 DoD's headline scenario, end to end through a real Spring context (no MQTT
 * broker needed — a command dispatch failure there is already caught and logged by {@code
 * CommandServiceImpl}, so this only needs to observe the {@code commands}/{@code alerts}
 * rows): a smoke reading matching an enabled rule's condition asynchronously issues a
 * command and raises an alert, off the ingest request's own thread.
 */
@PostgresIntegrationTest
class RuleEngineIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceCredentialRepository credRepo;
  @Autowired DeviceScopeRepository scopeRepo;
  @Autowired RuleRepository ruleRepo;
  @Autowired CommandRepository commandRepo;
  @Autowired AlertRepository alertRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired PasswordEncoder encoder;

  @BeforeEach
  void seed() {
    commandRepo.deleteAll();
    alertRepo.deleteAll();
    ruleRepo.deleteAll();
    credRepo.deleteAll();
    scopeRepo.deleteAll();
    sensorRepo.deleteAll();
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();

    Device gateway = deviceRepo.save(Device.builder()
        .deviceId("gw_1").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());
    sensorRepo.save(Sensor.builder().sensorId("s_smoke_1").gateway(gateway).type("smoke").zone("office_1").build());
    deviceRepo.save(Device.builder()
        .deviceId("act_exhaust_1").category(Device.Category.actuator).deviceType("exhst_fan")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());

    credRepo.save(DeviceCredential.builder().device(gateway).clientId("cli_gw_1")
        .clientSecretHash(encoder.encode("device-secret")).build());
    scopeRepo.save(DeviceScope.builder().deviceId("gw_1").scope("telemetry:publish").build());

    ruleRepo.save(Rule.builder().name("Smoke rule").enabled(true)
        .condition("office_1.smoke == true")
        .action("command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)")
        .priority(0).createdBy("admin-1").build());
  }

  @Test
  void smoke_reading_asynchronously_fires_the_rule_issuing_a_command_and_raising_an_alert() throws Exception {
    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_smoke_1","sensorType":"smoke","valueBool":true,"ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isAccepted());

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(commandRepo.findAll()).anySatisfy(c -> {
          assertThat(c.getTarget().getDeviceId()).isEqualTo("act_exhaust_1");
          assertThat(c.getAction()).isEqualTo("SET");
          assertThat(c.getParameters()).containsEntry("status", "ON");
          assertThat(c.getIssuedBy()).isNotBlank();
        }));

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(alertRepo.findAll()).anySatisfy(a -> {
          assertThat(a.getType()).isEqualTo("SMOKE");
          assertThat(a.getSeverity()).isEqualTo(Alert.Severity.CRITICAL);
          assertThat(a.getStatus()).isEqualTo(Alert.Status.OPEN);
        }));
  }

  @Test
  void disabled_rule_never_fires() throws Exception {
    ruleRepo.findAll().forEach(r -> {
      r.setEnabled(false);
      ruleRepo.save(r);
    });

    String device = deviceToken();
    mvc.perform(post("/api/v1/telemetry")
            .header("Authorization", "Bearer " + device)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"gatewayId":"gw_1","zone":"office_1","readings":[
                  {"sensorId":"s_smoke_1","sensorType":"smoke","valueBool":true,"ts":"2026-06-25T10:30:00Z"}
                ]}"""))
        .andExpect(status().isAccepted());

    // Give the worker a real chance to (wrongly) fire before asserting the negative.
    Thread.sleep(2000);
    assertThat(commandRepo.count()).isZero();
    assertThat(alertRepo.count()).isZero();
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
}
