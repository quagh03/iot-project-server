package com.huylq.iotprojectserver.api.dto.auth;

public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String refreshToken,
    String role) {
}
