package com.huylq.iotprojectserver.mqtt;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Wire shape of the MQTT telemetry payload (device-team spec §4.1) — {@code snake_case},
 * distinct from the HTTP {@code camelCase}/{@code readings[]} shape. {@code sensors[].value}
 * is polymorphic (numeric for temp/hmid/light, boolean for smoke/open), so it's read as
 * {@code Object} and branched on in {@link TelemetryMqttListener}.
 */
public record MqttTelemetryPayload(
    OffsetDateTime timestamp,
    String zone,
    @JsonProperty("gateway_id") String gatewayId,
    List<SensorReading> sensors) {

  public record SensorReading(String id, String type, Object value, String unit) {
  }
}
