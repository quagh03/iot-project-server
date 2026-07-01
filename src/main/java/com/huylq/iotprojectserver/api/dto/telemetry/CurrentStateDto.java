package com.huylq.iotprojectserver.api.dto.telemetry;

import com.huylq.iotprojectserver.telemetry.SensorLatest;

import java.time.OffsetDateTime;

/**
 * Current-state item (API §6 dashboard hot path) — served from {@code sensor_latest},
 * never the telemetry partitions.
 */
public record CurrentStateDto(
    String sensorId,
    String zone,
    String sensorType,
    Double valueNum,
    Boolean valueBool,
    String unit,
    OffsetDateTime ts) {

  public static CurrentStateDto from(SensorLatest s) {
    return new CurrentStateDto(s.getSensorId(), s.getZone(), s.getSensorType(), s.getValueNum(),
        s.getValueBool(), s.getUnit(), s.getTs());
  }
}
