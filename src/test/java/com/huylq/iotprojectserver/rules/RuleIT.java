package com.huylq.iotprojectserver.rules;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class RuleIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired SecurityTestSupport support;
  @Autowired RuleRepository ruleRepo;
  @Autowired UserRepository userRepo;
  @Autowired RefreshTokenRepository refreshRepo;

  @BeforeEach
  void seed() {
    ruleRepo.deleteAll();
    refreshRepo.deleteAll();
    userRepo.deleteAll();
    support.createUser("admin", "s3cret-string-32-bytes-long-now", Role.ADMIN);
    support.createUser("operator", "s3cret-string-32-bytes-long-now", Role.OPERATOR);
    support.createUser("viewer", "s3cret-string-32-bytes-long-now", Role.VIEWER);
  }

  @Test
  void admin_can_create_and_read_a_rule() throws Exception {
    String admin = loginAs("admin");
    String body = mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Smoke rule","condition":"office_1.smoke == true",
                 "action":"command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)","priority":10}"""))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.name").value("Smoke rule"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.priority").value(10))
        .andExpect(jsonPath("$.createdBy").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    String ruleId = (String) support.parseJson(body).get("ruleId");

    String operator = loginAs("operator");
    mvc.perform(get("/api/v1/rules/" + ruleId).header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.condition").value("office_1.smoke == true"));
  }

  @Test
  void viewer_cannot_create_or_read_rules() throws Exception {
    String viewer = loginAs("viewer");
    mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + viewer)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"x","condition":"office_1.smoke == true","action":"alert(SMOKE, CRITICAL)"}"""))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/rules").header("Authorization", "Bearer " + viewer))
        .andExpect(status().isForbidden());
  }

  @Test
  void operator_cannot_create_rules_but_can_list() throws Exception {
    String operator = loginAs("operator");
    mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + operator)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"x","condition":"office_1.smoke == true","action":"alert(SMOKE, CRITICAL)"}"""))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/rules").header("Authorization", "Bearer " + operator))
        .andExpect(status().isOk());
  }

  @Test
  void malformed_condition_is_unprocessable() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"bad","condition":"office_1 smoke == true","action":"alert(SMOKE, CRITICAL)"}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void malformed_action_is_unprocessable() throws Exception {
    String admin = loginAs("admin");
    mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"bad","condition":"office_1.smoke == true","action":"T(java.lang.Runtime).exec()"}"""))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void put_replaces_condition_and_action() throws Exception {
    String admin = loginAs("admin");
    String ruleId = createRule(admin);

    mvc.perform(put("/api/v1/rules/" + ruleId)
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Renamed","condition":"office_1.temp > 30","action":"alert(HEAT, WARNING)","priority":1}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Renamed"))
        .andExpect(jsonPath("$.condition").value("office_1.temp > 30"));
  }

  @Test
  void patch_toggles_enabled_without_touching_condition() throws Exception {
    String admin = loginAs("admin");
    String ruleId = createRule(admin);

    mvc.perform(patch("/api/v1/rules/" + ruleId)
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"enabled": false}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.condition").value("office_1.smoke == true"));
  }

  @Test
  void patch_with_no_fields_is_unprocessable() throws Exception {
    String admin = loginAs("admin");
    String ruleId = createRule(admin);

    mvc.perform(patch("/api/v1/rules/" + ruleId)
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void delete_removes_the_rule() throws Exception {
    String admin = loginAs("admin");
    String ruleId = createRule(admin);

    mvc.perform(delete("/api/v1/rules/" + ruleId).header("Authorization", "Bearer " + admin))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/rules/" + ruleId).header("Authorization", "Bearer " + admin))
        .andExpect(status().isNotFound());
    assertThat(ruleRepo.count()).isZero();
  }

  @Test
  void list_filters_by_enabled() throws Exception {
    String admin = loginAs("admin");
    String enabledId = createRule(admin);
    mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Disabled rule","enabled":false,"condition":"office_1.temp > 30","action":"alert(HEAT, WARNING)"}"""))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/rules").param("enabled", "true").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].ruleId").value(enabledId));
  }

  private String createRule(String admin) throws Exception {
    String body = mvc.perform(post("/api/v1/rules")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Smoke rule","condition":"office_1.smoke == true",
                 "action":"command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)","priority":10}"""))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return (String) support.parseJson(body).get("ruleId");
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
