package com.huylq.iotprojectserver.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT issuer settings. The {@code secret} must be at least 32 bytes for HS256;
 * production deployments must provide it via environment variable, never source.
 */
@ConfigurationProperties("iot.security.jwt")
public record JwtConfig(
        String issuer,
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration deviceTokenTtl) {

    public JwtConfig {
        if (issuer == null || issuer.isBlank()) issuer = "iot-platform";
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofHours(1);
        if (refreshTokenTtl == null) refreshTokenTtl = Duration.ofDays(30);
        if (deviceTokenTtl == null) deviceTokenTtl = Duration.ofHours(1);
    }
}
