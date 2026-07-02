package com.huylq.iotprojectserver.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bounded-window cap for {@code GET /v1/audit-logs} (API §10, non-negotiable invariant
 * #6 — partitioned-table reads require a bounded window, same rule as {@code telemetry}).
 * No value is pinned by the design docs; audit queries are typically wider-scoped
 * investigations than telemetry charts, so the default is more generous.
 */
@ConfigurationProperties("iot.audit")
public record AuditQueryProperties(Duration historyMaxWindow) {

  public AuditQueryProperties {
    if (historyMaxWindow == null) historyMaxWindow = Duration.ofDays(90);
  }
}
