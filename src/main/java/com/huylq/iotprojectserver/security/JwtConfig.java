package com.huylq.iotprojectserver.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT issuer settings. Signing/verification key material lives in {@link
 * JwtKeyProperties} (asymmetric, KMS-backed in production), not here.
 */
@ConfigurationProperties("iot.security.jwt")
public record JwtConfig(
    String issuer,
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
