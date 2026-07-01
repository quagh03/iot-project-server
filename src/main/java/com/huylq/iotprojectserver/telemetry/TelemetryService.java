package com.huylq.iotprojectserver.telemetry;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The one ingestion funnel (System Design §5.4) — the MQTT telemetry listener and
 * {@code POST /v1/telemetry} both call {@link #ingest}. Owns write access to
 * {@code telemetry}/{@code sensor_latest}.
 */
public interface TelemetryService {

  void ingest(TelemetryIngestCommand command);

  TelemetryPage queryHistory(String sensorId, String zone, OffsetDateTime from, OffsetDateTime to,
                            String cursor, int pageSize);

  List<SensorLatest> currentState(String zone);

  SensorLatest latest(String sensorId);
}
