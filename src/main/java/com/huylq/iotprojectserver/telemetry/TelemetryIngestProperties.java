package com.huylq.iotprojectserver.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Ingest-time integrity thresholds (System Design §7 stale-replay defense) and the
 * bounded-window cap for {@code GET /v1/telemetry} (API §5). No threshold is pinned by
 * the design docs — these are configurable, conservative defaults.
 */
@ConfigurationProperties("iot.telemetry")
public record TelemetryIngestProperties(
    Duration maxClockSkewFuture,
    Duration maxClockSkewPast,
    Duration historyMaxWindow) {

  public TelemetryIngestProperties {
    if (maxClockSkewFuture == null) maxClockSkewFuture = Duration.ofMinutes(5);
    if (maxClockSkewPast == null) maxClockSkewPast = Duration.ofHours(1);
    if (historyMaxWindow == null) historyMaxWindow = Duration.ofDays(7);
  }
}
