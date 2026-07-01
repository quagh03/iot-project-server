package com.huylq.iotprojectserver.health;

import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class ConnectivityIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceHealthRepository healthRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void seed() {
    healthRepo.deleteAll();
    sensorRepo.deleteAll();
    // Child (sensor) devices reference their gateway via a self-FK (ON DELETE RESTRICT),
    // so delete devices that have a parent before the gateways themselves.
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();

    Device gw1 = deviceRepo.save(Device.builder()
        .deviceId("gw_1").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());
    Device sensorDevice = deviceRepo.save(Device.builder()
        .deviceId("s_temp_1").category(Device.Category.sensor).deviceType("temp")
        .zone("office_1").parentGateway(gw1).status(Device.Status.ACTIVE).protocols(new String[0]).build());
    // gw_2 in office_2 deliberately gets no DeviceHealth row — must still count as offline.
    deviceRepo.save(Device.builder()
        .deviceId("gw_2").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_2").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());

    // Plain SQL rather than a JPA save() — DeviceHealth's @MapsId derived-identifier
    // mapping doesn't play well with Spring Data's merge-vs-persist heuristic for a
    // manually assigned id, and the repository's own @Modifying upsert needs a caller
    // transaction that a bare @BeforeEach doesn't have.
    jdbc.update("""
        INSERT INTO device_health (device_id, connection_status, last_seen, updated_at)
        VALUES (?, ?, ?, now())
        """, gw1.getDeviceId(), "ONLINE", java.sql.Timestamp.from(
        OffsetDateTime.parse("2026-06-25T10:29:50Z").toInstant()));
    jdbc.update("""
        INSERT INTO device_health (device_id, connection_status, last_seen, updated_at)
        VALUES (?, ?, ?, now())
        """, sensorDevice.getDeviceId(), "OFFLINE", java.sql.Timestamp.from(
        OffsetDateTime.parse("2026-06-25T09:00:00Z").toInstant()));

    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void rollup_without_filter_covers_every_zone_including_devices_with_no_health_row() throws Exception {
    String viewer = loginAs("viewer");
    // Repository orders by zone, so office_1 sorts before office_2.
    mvc.perform(get("/api/v1/connectivity").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].zone").value("office_1"))
        .andExpect(jsonPath("$.data[0].online").value(1))
        .andExpect(jsonPath("$.data[0].offline").value(1))
        .andExpect(jsonPath("$.data[0].total").value(2))
        .andExpect(jsonPath("$.data[1].zone").value("office_2"))
        .andExpect(jsonPath("$.data[1].online").value(0))
        .andExpect(jsonPath("$.data[1].offline").value(1))
        .andExpect(jsonPath("$.data[1].total").value(1));
  }

  @Test
  void rollup_with_zone_filter_returns_only_that_zone() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/connectivity").param("zone", "office_2")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].zone").value("office_2"))
        .andExpect(jsonPath("$.data[0].online").value(0))
        .andExpect(jsonPath("$.data[0].offline").value(1));
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
