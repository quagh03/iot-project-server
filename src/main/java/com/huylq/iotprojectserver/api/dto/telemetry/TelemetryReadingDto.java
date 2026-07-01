package com.huylq.iotprojectserver.api.dto.telemetry;

import com.huylq.iotprojectserver.telemetry.Telemetry;

import java.time.OffsetDateTime;

/**
 * History item (API §5 {@code GET /v1/telemetry}). Never exposes the internal
 * {@code telemetry.id} surrogate PK.
 */
public record TelemetryReadingDto(
    String sensorId,
    String sensorType,
    Double valueNum,
    Boolean valueBool,
    String unit,
    OffsetDateTime ts) {

  public static TelemetryReadingDto from(Telemetry t) {
    return new TelemetryReadingDto(t.getSensorId(), t.getSensorType(), t.getValueNum(),
        t.getValueBool(), t.getUnit(), t.getTs());
  }
}
