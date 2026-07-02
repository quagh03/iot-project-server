package com.huylq.iotprojectserver.mqtt;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Wire shape of the MQTT heartbeat payload (device-team spec §4.4) — {@code snake_case},
 * mirroring {@link MqttTelemetryPayload}.
 */
public record MqttHeartbeatPayload(
    @JsonProperty("device_id") String deviceId,
    OffsetDateTime timestamp,
    String status,
    @JsonProperty("firmware_version") String firmwareVersion,
    @JsonProperty("memory_usage_pct") Short memoryUsagePct,
    @JsonProperty("cpu_usage_pct") Short cpuUsagePct,
    @JsonProperty("wifi_rssi") Short wifiRssi) {
}
