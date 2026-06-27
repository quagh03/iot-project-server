package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.auth.DeviceTokenRequest;
import com.huylq.iotprojectserver.api.dto.auth.DeviceTokenResponse;
import com.huylq.iotprojectserver.security.device.DeviceTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth2 device token endpoint — standard {@code application/x-www-form-urlencoded} body.
 *
 * <p>Devices send {@code grant_type=client_credentials} plus their {@code client_id} /
 * {@code client_secret}. Granted scopes are the intersection of what the device is
 * authorized for and what was requested (omitted = all stored).
 */
@RestController
@RequestMapping("/api/v1/oauth2/token")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<DeviceTokenResponse> token(@Valid DeviceTokenRequest request) {
        DeviceTokenService.DeviceTokenResult result =
                deviceTokenService.mint(request.client_id(), request.client_secret(),
                        request.requestedScopes());

        DeviceTokenResponse tokenResponse = DeviceTokenResponse.builder()
            .accessToken(result.accessToken())
            .tokenType("Bearer")
            .expiresIn(result.expiresInSeconds())
            .scope(String.join(" ", result.grantedScopes()))
            .build();

        return ResponseEntity.ok(tokenResponse);
    }
}
