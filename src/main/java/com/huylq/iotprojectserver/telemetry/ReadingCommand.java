package com.huylq.iotprojectserver.telemetry;

import java.time.OffsetDateTime;

/**
 * One sensor reading, decoupled from both wire shapes (HTTP camelCase / MQTT snake_case)
 * so {@link TelemetryService} has a single input shape regardless of transport.
 */
public record ReadingCommand(
    String sensorId,
    String sensorType,
    Double valueNum,
    Boolean valueBool,
    String unit,
    OffsetDateTime ts) {
}
