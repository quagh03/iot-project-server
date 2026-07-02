package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.health.HealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

/**
 * Consumes the broker-published Last Will & Testament on {@code iot/status/{device_id}}
 * (System Design §6/§8). The broker only publishes here on an ungraceful disconnect, so
 * any message on this topic is treated as an offline signal regardless of payload shape
 * — the LWT body isn't pinned by the device-team spec (§4.6). Presence going back
 * {@code ONLINE} is driven by the next heartbeat/telemetry, not by this listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresenceMqttListener implements IMqttMessageListener {

  private final HealthService healthService;

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    // Never let an exception escape this callback — see HeartbeatMqttListener.
    try {
      handle(topic);
    } catch (Exception e) {
      log.error("Dropping malformed presence message on topic {}: {}", topic, e.getMessage());
    }
  }

  private void handle(String topic) {
    String[] segments = topic.split("/");
    if (segments.length != 3 || !"status".equals(segments[1])) {
      log.warn("Unexpected presence topic shape: {}", topic);
      return;
    }
    String deviceId = segments[2];
    healthService.markOffline(deviceId);
  }
}
