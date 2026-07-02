package com.huylq.iotprojectserver.security;

import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.security.device.DeviceCredential;
import com.huylq.iotprojectserver.security.device.DeviceScope;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.security.device.DeviceCredentialRepository;
import com.huylq.iotprojectserver.security.device.DeviceScopeRepository;
import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@PostgresIntegrationTest
class DeviceTokenIT {

    @Autowired MockMvc mvc;
    @Autowired DeviceRepository deviceRepo;
    @Autowired DeviceCredentialRepository credRepo;
    @Autowired DeviceScopeRepository scopeRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired CommandRepository commandRepo;

    @BeforeEach
    void seed() {
        commandRepo.deleteAll();
        credRepo.deleteAll();
        scopeRepo.deleteAll();
        deviceRepo.deleteAll();

        Device device = deviceRepo.save(Device.builder()
                .deviceId("gw_test_1")
                .category(Device.Category.gateway)
                .deviceType("gateway")
                .zone("z1")
                .status(Device.Status.ACTIVE)
                .protocols(new String[]{"mqtt"})
                .build());

        credRepo.save(DeviceCredential.builder()
                .device(device)
                .clientId("cli_gw_test_1")
                .clientSecretHash(encoder.encode("device-secret"))
                .build());

        scopeRepo.save(DeviceScope.builder()
                .deviceId(device.getDeviceId())
                .scope("telemetry:publish").build());
        scopeRepo.save(DeviceScope.builder()
                .deviceId(device.getDeviceId())
                .scope("heartbeat:publish").build());
    }

    @Test
    void valid_client_credentials_mint_token_with_all_stored_scopes() throws Exception {
        mvc.perform(post("/api/v1/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "cli_gw_test_1")
                        .param("client_secret", "device-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.containsString("telemetry:publish")))
                .andExpect(jsonPath("$.scope").value(org.hamcrest.Matchers.containsString("heartbeat:publish")));
    }

    @Test
    void requested_scope_subset_is_granted_via_intersection() throws Exception {
        mvc.perform(post("/api/v1/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "cli_gw_test_1")
                        .param("client_secret", "device-secret")
                        .param("scope", "telemetry:publish command:subscribe"))
                .andExpect(status().isOk())
                // command:subscribe wasn't stored, so it's filtered out
                .andExpect(jsonPath("$.scope").value("telemetry:publish"));
    }

    @Test
    void wrong_secret_returns_401() throws Exception {
        mvc.perform(post("/api/v1/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", "cli_gw_test_1")
                        .param("client_secret", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://api.iot.example.com/errors/unauthenticated"));
    }
}
