package com.huylq.iotprojectserver.health;

import java.time.OffsetDateTime;

/**
 * A device's health upsert, transport-agnostic. {@code authenticatedDeviceId} is the JWT
 * subject for the HTTP path; MQTT has no broker-asserted identity yet (§7 — broker ACLs
 * are Phase 10), so it's {@code null} there — the topic-embedded {@code device_id}
 * already served as the cross-check before this command is built.
 */
public record HeartbeatCommand(
    String deviceId,
    String authenticatedDeviceId,
    Short memoryUsagePct,
    Short cpuUsagePct,
    Short wifiRssi,
    OffsetDateTime ts) {
}
