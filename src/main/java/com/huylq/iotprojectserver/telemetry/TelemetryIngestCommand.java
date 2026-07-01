package com.huylq.iotprojectserver.telemetry;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A gateway's ingest batch, transport-agnostic. {@code authenticatedDeviceId} is the
 * JWT subject for the HTTP path; MQTT has no broker-asserted identity yet (§7 — broker
 * ACLs are Phase 10), so it's {@code null} there and {@link TelemetryServiceImpl} falls
 * back to a registry cross-check instead of a token-identity comparison.
 */
public record TelemetryIngestCommand(
    String zone,
    String gatewayId,
    List<ReadingCommand> readings,
    String authenticatedDeviceId,
    OffsetDateTime receivedAt) {
}
