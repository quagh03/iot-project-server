package com.huylq.iotprojectserver.security.user;

/**
 * Tokens returned from a successful login or refresh.
 */
public record IssuedTokens(String accessToken, long accessTokenTtlSeconds,
                           String refreshToken, String role) {
}
