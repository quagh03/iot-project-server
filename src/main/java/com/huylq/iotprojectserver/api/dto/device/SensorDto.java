package com.huylq.iotprojectserver.api.dto.device;

import com.huylq.iotprojectserver.registry.Sensor;

/**
 * Sensor view returned by {@code GET /devices/{deviceId}/sensors} (OpenAPI {@code Sensor}).
 */
public record SensorDto(
    String sensorId,
    String gatewayId,
    String type,
    String zone) {

  public static SensorDto from(Sensor s) {
    return new SensorDto(s.getSensorId(), s.getGateway().getDeviceId(), s.getType(), s.getZone());
  }
}
