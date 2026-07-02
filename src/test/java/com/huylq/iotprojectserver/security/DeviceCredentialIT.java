package com.huylq.iotprojectserver.security;

import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.SensorRepository;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class DeviceCredentialIT {

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
  void setup() throws Exception {
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
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
    admin = loginAs("admin");
    register("gw_1");
    activate("gw_1");
  }

  @Test
  void issue_returns_secret_once_then_metadata_hides_it() throws Exception {
    String body = mvc.perform(post("/api/v1/devices/gw_1/credentials").header("Authorization", "Bearer " + admin))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.clientId").exists())
        .andExpect(jsonPath("$.clientSecret").exists())
        .andExpect(jsonPath("$.graceExpiresAt").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    Map<String, Object> issued = support.parseJson(body);

    // Re-issue → 409
    mvc.perform(post("/api/v1/devices/gw_1/credentials").header("Authorization", "Bearer " + admin))
        .andExpect(status().isConflict());

    // Metadata view never exposes the secret
    mvc.perform(get("/api/v1/devices/gw_1/credentials").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientId").value(issued.get("clientId")))
        .andExpect(jsonPath("$.rotatedAt").exists())
        .andExpect(jsonPath("$.clientSecret").doesNotExist());
  }

  @Test
  void issued_credential_mints_a_device_token() throws Exception {
    Map<String, Object> issued = issueCredential("gw_1");
    assertTokenMintable((String) issued.get("clientId"), (String) issued.get("clientSecret"), true);
  }

  @Test
  void rotate_keeps_old_secret_valid_during_grace_window() throws Exception {
    Map<String, Object> issued = issueCredential("gw_1");
    String clientId = (String) issued.get("clientId");
    String oldSecret = (String) issued.get("clientSecret");

    String rotatedBody = mvc.perform(post("/api/v1/devices/gw_1/credentials:rotate")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecret").exists())
        .andExpect(jsonPath("$.graceExpiresAt").exists())
        .andReturn().getResponse().getContentAsString();
    String newSecret = (String) support.parseJson(rotatedBody).get("clientSecret");

    // Both old (grace) and new secrets mint tokens
    assertTokenMintable(clientId, newSecret, true);
    assertTokenMintable(clientId, oldSecret, true);
  }

  @Test
  void scopes_get_then_replace_then_unknown_rejected() throws Exception {
    issueCredential("gw_1");

    mvc.perform(get("/api/v1/devices/gw_1/scopes").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scopes.length()").value(0));

    mvc.perform(put("/api/v1/devices/gw_1/scopes").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"scopes":["telemetry:publish","heartbeat:publish"]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scopes.length()").value(2));

    mvc.perform(get("/api/v1/devices/gw_1/scopes").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scopes", org.hamcrest.Matchers.containsInAnyOrder(
            "telemetry:publish", "heartbeat:publish")));

    // Full replace shrinks the set
    mvc.perform(put("/api/v1/devices/gw_1/scopes").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"scopes":["telemetry:publish"]}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scopes.length()").value(1));

    // Unknown scope → 422
    mvc.perform(put("/api/v1/devices/gw_1/scopes").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"scopes":["telemetry:publish","root:everything"]}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void suspended_device_cannot_mint_token() throws Exception {
    Map<String, Object> issued = issueCredential("gw_1");
    // suspend the device
    mvc.perform(post("/api/v1/devices/gw_1:suspend").header("Authorization", "Bearer " + admin))
        .andExpect(status().isNoContent());

    assertTokenMintable((String) issued.get("clientId"), (String) issued.get("clientSecret"), false);
  }

  @Test
  void idempotent_issue_returns_same_secret_and_one_credential() throws Exception {
    String key = UUID.randomUUID().toString();
    String first = mvc.perform(post("/api/v1/devices/gw_1/credentials")
            .header("Authorization", "Bearer " + admin)
            .header("Idempotency-Key", key))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String replay = mvc.perform(post("/api/v1/devices/gw_1/credentials")
            .header("Authorization", "Bearer " + admin)
            .header("Idempotency-Key", key))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(support.parseJson(replay).get("clientSecret"))
        .isEqualTo(support.parseJson(first).get("clientSecret"));
  }

  @Test
  void viewer_cannot_touch_credentials() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(post("/api/v1/devices/gw_1/credentials").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/devices/gw_1/scopes").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
  }

  // ---- helpers ----

  private Map<String, Object> issueCredential(String deviceId) throws Exception {
    String body = mvc.perform(post("/api/v1/devices/" + deviceId + "/credentials")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return support.parseJson(body);
  }

  private void assertTokenMintable(String clientId, String secret, boolean expectOk) throws Exception {
    var result = mvc.perform(post("/api/v1/oauth2/token")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .param("grant_type", "client_credentials")
        .param("client_id", clientId)
        .param("client_secret", secret));
    if (expectOk) {
      result.andExpect(status().isOk()).andExpect(jsonPath("$.access_token").exists());
    } else {
      result.andExpect(status().isUnauthorized());
    }
  }

  private void register(String id) throws Exception {
    mvc.perform(post("/api/v1/devices").header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"deviceId\":\"" + id + "\",\"category\":\"gateway\",\"deviceType\":\"gateway\",\"zone\":\"z\"}"))
        .andExpect(status().isCreated());
  }

  private void activate(String id) throws Exception {
    mvc.perform(post("/api/v1/devices/" + id + ":activate").header("Authorization", "Bearer " + admin))
        .andExpect(status().isNoContent());
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
