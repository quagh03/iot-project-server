package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
import com.huylq.iotprojectserver.registry.SensorRepository;
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
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class TelemetryHistoryQueryIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired TelemetryRepository telemetryRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired CommandRepository commandRepo;

  private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 6, 25, 10, 0, 0, 0, ZoneOffset.UTC);

  @BeforeEach
  void seed() {
    commandRepo.deleteAll();
    telemetryRepo.deleteAll();
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
    sensorRepo.save(Sensor.builder().sensorId("s_temp_1").gateway(gateway).type("temp").zone("office_1").build());

    for (int i = 0; i < 5; i++) {
      telemetryRepo.save(Telemetry.builder()
          .ts(BASE.plusMinutes(i))
          .zone("office_1").gatewayId("gw_1").sensorId("s_temp_1").sensorType("temp")
          .valueNum(20.0 + i).build());
    }

    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void neither_sensorId_nor_zone_returns_422() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("from", "2026-06-24T00:00:00Z").param("to", "2026-06-26T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void both_sensorId_and_zone_returns_422() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1").param("zone", "office_1")
            .param("from", "2026-06-24T00:00:00Z").param("to", "2026-06-26T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void missing_window_returns_422() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void oversized_window_returns_422() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1")
            .param("from", "2026-01-01T00:00:00Z").param("to", "2026-12-01T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void pagination_walks_all_pages_newest_first_without_duplicates_or_gaps() throws Exception {
    String viewer = loginAs("viewer");

    String page1Body = mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1").param("pageSize", "2")
            .param("from", "2026-06-25T00:00:00Z").param("to", "2026-06-26T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.page.hasMore").value(true))
        .andExpect(jsonPath("$.data[0].valueNum").value(24.0))
        .andExpect(jsonPath("$.data[1].valueNum").value(23.0))
        .andReturn().getResponse().getContentAsString();
    String cursor = (String) ((Map<?, ?>) support.parseJson(page1Body).get("page")).get("nextCursor");

    String page2Body = mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1").param("pageSize", "2").param("cursor", cursor)
            .param("from", "2026-06-25T00:00:00Z").param("to", "2026-06-26T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.page.hasMore").value(true))
        .andExpect(jsonPath("$.data[0].valueNum").value(22.0))
        .andExpect(jsonPath("$.data[1].valueNum").value(21.0))
        .andReturn().getResponse().getContentAsString();
    String cursor2 = (String) ((Map<?, ?>) support.parseJson(page2Body).get("page")).get("nextCursor");

    mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1").param("pageSize", "2").param("cursor", cursor2)
            .param("from", "2026-06-25T00:00:00Z").param("to", "2026-06-26T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.page.hasMore").value(false))
        .andExpect(jsonPath("$.data[0].valueNum").value(20.0));
  }

  @Test
  void unbounded_cursor_malformed_returns_422() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/telemetry")
            .header("Authorization", "Bearer " + viewer)
            .param("sensorId", "s_temp_1").param("cursor", "not-a-cursor!!")
            .param("from", "2026-06-25T00:00:00Z").param("to", "2026-06-26T00:00:00Z"))
        .andExpect(status().isUnprocessableEntity());
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
