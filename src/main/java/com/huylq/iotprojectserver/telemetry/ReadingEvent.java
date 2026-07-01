package com.huylq.iotprojectserver.telemetry;

import java.time.OffsetDateTime;

/**
 * Rule hand-off seam payload (System Design §5.6). Shape is provisional — Phase 7 owns
 * the real consumer and may redesign this once the evaluator exists.
 */
public record ReadingEvent(
    String sensorId,
    String sensorType,
    Double valueNum,
    Boolean valueBool,
    String zone,
    OffsetDateTime ts) {
}
