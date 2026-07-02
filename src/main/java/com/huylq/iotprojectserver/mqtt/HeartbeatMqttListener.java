package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.health.HealthService;
import com.huylq.iotprojectserver.health.HeartbeatCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

/**
 * MQTT half of the heartbeat funnel (implementation plan Phase 5) — parses
 * {@code iot/heartbeat/{device_id}} and calls the same {@link HealthService} the HTTP
 * fallback uses. MQTT has no broker-asserted identity yet (§7 — broker ACLs are Phase
 * 10), so there's no JWT-style identity to pass through; the topic-embedded
 * {@code device_id} is cross-checked against the payload instead, mirroring
 * {@link TelemetryMqttListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatMqttListener implements IMqttMessageListener {

  private final HealthService healthService;
  private final ObjectMapper json;

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    // Never let an exception escape this callback — Paho kills the delivery thread for
    // the whole connection if one does, and there's no response channel to report a
    // 422-equivalent to the sender anyway. Log and drop instead.
    try {
      handle(topic, message);
    } catch (Exception e) {
      log.error("Dropping malformed heartbeat message on topic {}: {}", topic, e.getMessage());
    }
  }

  private void handle(String topic, MqttMessage message) {
    String[] segments = topic.split("/");
    if (segments.length != 3 || !"heartbeat".equals(segments[1])) {
      log.warn("Unexpected heartbeat topic shape: {}", topic);
      return;
    }
    String topicDeviceId = segments[2];

    MqttHeartbeatPayload payload = json.readValue(message.getPayload(), MqttHeartbeatPayload.class);
    if (!topicDeviceId.equals(payload.deviceId())) {
      log.warn("Heartbeat topic/payload deviceId mismatch: topic={} payload={}",
          topicDeviceId, payload.deviceId());
      return;
    }

    OffsetDateTime ts = payload.timestamp() != null ? payload.timestamp() : Clocks.nowUtc();
    healthService.upsertHeartbeat(new HeartbeatCommand(topicDeviceId, null,
        payload.memoryUsagePct(), payload.cpuUsagePct(), payload.wifiRssi(), ts));
  }
}
