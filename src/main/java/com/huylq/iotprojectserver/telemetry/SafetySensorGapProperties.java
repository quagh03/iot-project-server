package com.huylq.iotprojectserver.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Telemetry-gap detection on safety-classified sensor types (System Design §7: "telemetry
 * gap/anomaly on a safety sensor" is one of the required detection signals). {@code
 * smoke} is the only sensor type the design docs explicitly treat as safety-critical;
 * no gap threshold is pinned, so this is a conservative configurable default.
 */
@ConfigurationProperties("iot.telemetry.safety-gap")
public record SafetySensorGapProperties(List<String> sensorTypes, Duration maxAge) {

  public SafetySensorGapProperties {
    if (sensorTypes == null) sensorTypes = List.of("smoke");
    if (maxAge == null) maxAge = Duration.ofMinutes(10);
  }
}
