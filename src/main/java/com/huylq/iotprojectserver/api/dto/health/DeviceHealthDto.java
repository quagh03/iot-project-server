package com.huylq.iotprojectserver.api.dto.health;

import com.huylq.iotprojectserver.health.DeviceHealth;

import java.time.OffsetDateTime;

/**
 * Single-row health view (OpenAPI {@code DeviceHealth}, API §6 {@code GET /v1/devices/{deviceId}/health}).
 */
public record DeviceHealthDto(
    String deviceId,
    DeviceHealth.ConnectionStatus connectionStatus,
    OffsetDateTime lastSeen,
    Short memoryUsagePct,
    Short cpuUsagePct,
    Short wifiRssi,
    OffsetDateTime updatedAt) {

  public static DeviceHealthDto from(DeviceHealth h) {
    return new DeviceHealthDto(h.getDeviceId(), h.getConnectionStatus(), h.getLastSeen(),
        h.getMemoryUsagePct(), h.getCpuUsagePct(), h.getWifiRssi(), h.getUpdatedAt());
  }
}
