package com.huylq.iotprojectserver.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Replay window for {@code Idempotency-Key} reuse. Default 24 h per API §1.
 */
@ConfigurationProperties("iot.idempotency")
public record IdempotencyConfig(int ttlHours) {

  public IdempotencyConfig {
    if (ttlHours <= 0) ttlHours = 24;
  }
}
