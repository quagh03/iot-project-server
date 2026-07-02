package com.huylq.iotprojectserver.api.dto.health;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * HTTP fallback heartbeat payload (OpenAPI {@code HeartbeatRequest}, API §7). {@code deviceId}
 * must match the authenticated device identity — enforced in {@code HealthService}, not here.
 */
public record HeartbeatRequest(
    @NotBlank @Size(max = 64) String deviceId,
    @Min(0) @Max(100) Short memoryUsagePct,
    @Min(0) @Max(100) Short cpuUsagePct,
    Short wifiRssi) {
}
