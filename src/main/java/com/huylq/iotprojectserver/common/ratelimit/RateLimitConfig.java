package com.huylq.iotprojectserver.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-category requests-per-minute limits (data spec §29).
 */
@ConfigurationProperties("iot.rate-limit")
public record RateLimitConfig(
    boolean enabled,
    int userPerMinute,
    int devicePerMinute,
    int authPerMinute,
    int telemetryPerMinute) {

  public RateLimitConfig {
    if (userPerMinute <= 0) userPerMinute = 100;
    if (devicePerMinute <= 0) devicePerMinute = 300;
    if (authPerMinute <= 0) authPerMinute = 20;
    if (telemetryPerMinute <= 0) telemetryPerMinute = 600;
  }
}
