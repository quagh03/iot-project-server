package com.huylq.iotprojectserver.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeviceTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("scope") String scope) {
}
