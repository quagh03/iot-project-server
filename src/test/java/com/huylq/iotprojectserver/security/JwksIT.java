package com.huylq.iotprojectserver.security;

import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PostgresIntegrationTest
class JwksIT {

  @Autowired MockMvc mvc;
  @Autowired JwtKeyManager keyManager;

  @Test
  void jwks_endpoint_is_public_and_exposes_only_the_active_kid_with_no_private_material() throws Exception {
    mvc.perform(get("/api/v1/.well-known/jwks.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keys").isArray())
        .andExpect(jsonPath("$.keys[0].kid").value(keyManager.activeKid()))
        .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
        .andExpect(jsonPath("$.keys[0].d").doesNotExist()); // 'd' is the RSA private exponent
  }
}
