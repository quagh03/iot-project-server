package com.huylq.iotprojectserver.mqtt;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Wire shape of the inbound MQTT command-ack payload (device-team spec §4.3, Flow 9) —
 * note the ack body uses {@code device_id}, not {@code target_id}, for the same concept
 * the command body calls {@code target_id}. {@code status} is {@code RECEIVED} (interim,
 * optional-but-recommended) or {@code SUCCESS}/{@code FAILED} (terminal, mandatory,
 * always carries {@code executed_at}).
 */
public record MqttCommandAckPayload(
    @JsonProperty("command_id") String commandId,
    @JsonProperty("device_id") String deviceId,
    String status,
    @JsonProperty("executed_at") OffsetDateTime executedAt) {
}
