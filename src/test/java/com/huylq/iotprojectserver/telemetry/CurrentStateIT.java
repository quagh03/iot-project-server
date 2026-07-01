package com.huylq.iotprojectserver.telemetry;

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
class CurrentStateIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired SensorLatestRepository sensorLatestRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;

  @BeforeEach
  void seed() {
    sensorLatestRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();

    sensorLatestRepo.save(SensorLatest.builder()
        .sensorId("s_temp_1").zone("office_1").sensorType("temp").valueNum(22.4).unit("C")
        .ts(OffsetDateTime.parse("2026-06-25T10:29:58Z")).build());
    sensorLatestRepo.save(SensorLatest.builder()
        .sensorId("s_temp_2").zone("office_2").sensorType("temp").valueNum(19.1).unit("C")
        .ts(OffsetDateTime.parse("2026-06-25T10:29:59Z")).build());

    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void current_state_without_filter_returns_all_sensors() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/current-state").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  void current_state_with_zone_filter_is_scoped() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/current-state").param("zone", "office_1")
            .header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].sensorId").value("s_temp_1"));
  }

  @Test
  void latest_for_known_sensor_returns_200() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/sensors/s_temp_1/latest").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.valueNum").value(22.4));
  }

  @Test
  void latest_for_unknown_sensor_returns_404() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/sensors/s_ghost/latest").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isNotFound());
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
