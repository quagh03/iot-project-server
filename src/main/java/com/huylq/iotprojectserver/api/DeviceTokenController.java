package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.auth.DeviceTokenResponse;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.error.ErrorType;
import com.huylq.iotprojectserver.security.device.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

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
    public ResponseEntity<DeviceTokenResponse> token(@RequestParam("grant_type") String grantType,
                                                     @RequestParam("client_id") String clientId,
                                                     @RequestParam("client_secret") String clientSecret,
                                                     @RequestParam(value = "scope", required = false) String scope) {
        if (!"client_credentials".equals(grantType)) {
            throw new ApiException(ErrorType.VALIDATION, HttpStatus.BAD_REQUEST,
                    "unsupported grant_type");
        }
        Set<String> requested = (scope == null || scope.isBlank())
                ? Set.of()
                : Arrays.stream(scope.trim().split("\\s+"))
                        .collect(Collectors.toUnmodifiableSet());

        DeviceTokenService.DeviceTokenResult result =
                deviceTokenService.mint(clientId, clientSecret, requested);

        return ResponseEntity.ok(new DeviceTokenResponse(
                result.accessToken(),
                "Bearer",
                result.expiresInSeconds(),
                String.join(" ", result.grantedScopes())));
    }
}
