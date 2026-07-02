package com.huylq.iotprojectserver.registry;

import com.huylq.iotprojectserver.command.CommandRepository;
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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class DeviceRegistryIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;
  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired CommandRepository commandRepo;

  @BeforeEach
  void clean() {
    commandRepo.deleteAll();
    sensorRepo.deleteAll();
    // Child (sensor) devices reference their gateway via a self-FK (ON DELETE RESTRICT),
    // so delete devices that have a parent before the gateways themselves.
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void admin_registers_gateway_then_reads_it() throws Exception {
    String admin = loginAs("admin");

    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_1","category":"gateway","deviceType":"gateway","zone":"office_1","protocols":["mqtt"]}"""))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/devices/gw_1"))
        .andExpect(jsonPath("$.deviceId").value("gw_1"))
        .andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.category").value("gateway"));

    mvc.perform(get("/api/v1/devices/gw_1").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.zone").value("office_1"));
  }

  @Test
  void registering_sensor_creates_sensor_row_visible_on_gateway() throws Exception {
    String admin = loginAs("admin");
    registerGateway(admin, "gw_1");

    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"s_temp_1","category":"sensor","deviceType":"temp","zone":"office_1","parentGatewayId":"gw_1"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.parentGatewayId").value("gw_1"));

    mvc.perform(get("/api/v1/devices/gw_1/sensors").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].sensorId").value("s_temp_1"))
        .andExpect(jsonPath("$.data[0].gatewayId").value("gw_1"));
  }

  @Test
  void duplicate_device_id_returns_409() throws Exception {
    String admin = loginAs("admin");
    registerGateway(admin, "gw_1");
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_1","category":"gateway","deviceType":"gateway","zone":"z"}"""))
        .andExpect(status().isConflict());
  }

  @Test
  void sensor_without_valid_parent_returns_422() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"s_x","category":"sensor","deviceType":"temp","zone":"z","parentGatewayId":"nope"}"""))
        .andExpect(status().isUnprocessableEntity());

    // sensor with no parent at all
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"s_y","category":"sensor","deviceType":"temp","zone":"z"}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void non_sensor_with_parent_returns_422() throws Exception {
    String admin = loginAs("admin");
    registerGateway(admin, "gw_1");
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"act_1","category":"actuator","deviceType":"exhst_fan","zone":"z","parentGatewayId":"gw_1"}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void patch_updates_metadata_but_list_filters_work() throws Exception {
    String admin = loginAs("admin");
    registerGateway(admin, "gw_1");

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .patch("/api/v1/devices/gw_1")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"firmwareVersion":"2.0.0","zone":"office_2"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firmwareVersion").value("2.0.0"))
        .andExpect(jsonPath("$.zone").value("office_2"));

    mvc.perform(get("/api/v1/devices").param("zone", "office_2")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.page.total").value(1));

    mvc.perform(get("/api/v1/devices").param("zone", "nowhere")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }

  @Test
  void empty_patch_returns_422() throws Exception {
    String admin = loginAs("admin");
    registerGateway(admin, "gw_1");
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .patch("/api/v1/devices/gw_1")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void get_unknown_device_returns_404() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(get("/api/v1/devices/ghost").header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
  }

  @Test
  void viewer_can_read_but_not_register() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(get("/api/v1/devices").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + viewer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"deviceId":"gw_x","category":"gateway","deviceType":"gateway","zone":"z"}"""))
        .andExpect(status().isForbidden());
  }

  @Test
  void unauthenticated_list_returns_401() throws Exception {
    mvc.perform(get("/api/v1/devices")).andExpect(status().isUnauthorized());
  }

  @Test
  void idempotent_register_replays_original_result() throws Exception {
    String admin = loginAs("admin");
    String key = UUID.randomUUID().toString();
    String body = """
        {"deviceId":"gw_idem","category":"gateway","deviceType":"gateway","zone":"z"}""";

    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    // Replay: same key + same body → original 201, no duplicate row, no 409.
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + admin)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.deviceId").value("gw_idem"));

    assertThat(deviceRepo.existsById("gw_idem")).isTrue();
    assertThat(deviceRepo.count()).isEqualTo(1);
  }

  private void registerGateway(String token, String id) throws Exception {
    mvc.perform(post("/api/v1/devices")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"" + id + "\",\"category\":\"gateway\",\"deviceType\":\"gateway\",\"zone\":\"office_1\"}"))
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
