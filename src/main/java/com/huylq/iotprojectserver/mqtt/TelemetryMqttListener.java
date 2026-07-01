package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.telemetry.ReadingCommand;
import com.huylq.iotprojectserver.telemetry.TelemetryIngestCommand;
import com.huylq.iotprojectserver.telemetry.TelemetryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * MQTT half of the one ingestion funnel (System Design §5.4) — parses
 * {@code iot/telemetry/{zone}/{gateway_id}} and calls the same {@link TelemetryService}
 * the HTTP fallback uses. MQTT has no broker-asserted identity yet (§7 — broker ACLs
 * are Phase 10), so there's no JWT-style identity to pass through; {@link TelemetryService}
 * falls back to a registry cross-check instead.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryMqttListener implements IMqttMessageListener {

  private final TelemetryService telemetryService;
  private final ObjectMapper json;

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    // Never let an exception escape this callback — Paho kills the delivery thread for
    // the whole connection if one does, and there's no response channel to report a
    // 422-equivalent to the sender anyway. Log and drop instead.
    try {
      handle(topic, message);
    } catch (Exception e) {
      log.error("Dropping malformed telemetry message on topic {}: {}", topic, e.getMessage());
    }
  }

  private void handle(String topic, MqttMessage message) {
    String[] segments = topic.split("/");
    if (segments.length != 4 || !"telemetry".equals(segments[1])) {
      log.warn("Unexpected telemetry topic shape: {}", topic);
      return;
    }
    String topicZone = segments[2];
    String topicGatewayId = segments[3];

    MqttTelemetryPayload payload = json.readValue(message.getPayload(), MqttTelemetryPayload.class);
    if (!topicGatewayId.equals(payload.gatewayId())) {
      log.warn("Telemetry topic/payload gatewayId mismatch: topic={} payload={}",
          topicGatewayId, payload.gatewayId());
      return;
    }

    List<ReadingCommand> readings = payload.sensors().stream()
        .map(s -> toReadingCommand(payload.timestamp(), s))
        .filter(Objects::nonNull)
        .toList();
    if (readings.isEmpty()) {
      log.warn("No usable readings in telemetry message on topic {}", topic);
      return;
    }

    telemetryService.ingest(new TelemetryIngestCommand(topicZone, topicGatewayId, readings, null,
        Clocks.nowUtc()));
  }

  private ReadingCommand toReadingCommand(OffsetDateTime ts, MqttTelemetryPayload.SensorReading s) {
    Double valueNum = null;
    Boolean valueBool = null;
    if (s.value() instanceof Boolean b) {
      valueBool = b;
    } else if (s.value() instanceof Number n) {
      valueNum = n.doubleValue();
    } else {
      log.warn("Unrecognized value type for sensor {}: {}", s.id(), s.value());
      return null;
    }
    return new ReadingCommand(s.id(), s.type(), valueNum, valueBool, s.unit(), ts);
  }
}
