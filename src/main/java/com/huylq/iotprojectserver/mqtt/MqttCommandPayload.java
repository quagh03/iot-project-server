package com.huylq.iotprojectserver.mqtt;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Wire shape of the outbound MQTT command payload (device-team spec §4.2, Flow 9) —
 * {@code snake_case}, mirroring {@link MqttTelemetryPayload}. {@code type} is the
 * device's own {@code device_type} (e.g. {@code ac}), registry-derived server-side —
 * never the caller-supplied request field.
 */
public record MqttCommandPayload(
    @JsonProperty("command_id") String commandId,
    @JsonProperty("target_id") String targetId,
    String type,
    String action,
    Map<String, Object> parameters) {
}
