package com.huylq.iotprojectserver.health;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Staleness-sweep threshold (System Design §6/§8) — defense-in-depth alongside LWT for a
 * device whose will never fired (e.g. the broker itself dropped without delivering it).
 * No threshold is pinned by the design docs — configurable, conservative default.
 */
@ConfigurationProperties("iot.health")
public record HealthSweepProperties(Duration staleAfter) {

  public HealthSweepProperties {
    if (staleAfter == null) staleAfter = Duration.ofMinutes(3);
  }
}
