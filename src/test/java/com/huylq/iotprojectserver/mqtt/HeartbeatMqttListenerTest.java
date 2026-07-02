package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.health.HealthService;
import com.huylq.iotprojectserver.health.HeartbeatCommand;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The one hard requirement for this listener: an exception must never escape
 * {@code messageArrived} — Paho kills the callback thread for the whole connection if
 * one does, and MQTT has no response channel to report a rejection anyway.
 */
@ExtendWith(MockitoExtension.class)
class HeartbeatMqttListenerTest {

  @Mock private HealthService healthService;

  private HeartbeatMqttListener listener;

  @BeforeEach
  void setUp() {
    listener = new HeartbeatMqttListener(healthService, JsonMapper.builder().build());
  }

  @Test
  void malformed_json_never_propagates() {
    MqttMessage message = new MqttMessage("{not valid json".getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/heartbeat/gw_1", message))
        .doesNotThrowAnyException();

    verify(healthService, never()).upsertHeartbeat(any());
  }

  @Test
  void malformed_topic_never_propagates() {
    String payload = """
        {"device_id":"gw_1","timestamp":"2026-06-25T10:30:00Z","status":"ONLINE"}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/heartbeat", message))
        .doesNotThrowAnyException();

    verify(healthService, never()).upsertHeartbeat(any());
  }

  @Test
  void topic_deviceId_not_matching_payload_deviceId_is_dropped_not_thrown() {
    String payload = """
        {"device_id":"gw_other","timestamp":"2026-06-25T10:30:00Z","status":"ONLINE"}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/heartbeat/gw_1", message))
        .doesNotThrowAnyException();

    verify(healthService, never()).upsertHeartbeat(any());
  }

  @Test
  void service_throwing_never_propagates_out_of_the_callback() {
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(healthService).upsertHeartbeat(any());
    String payload = """
        {"device_id":"gw_1","timestamp":"2026-06-25T10:30:00Z","status":"ONLINE"}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/heartbeat/gw_1", message))
        .doesNotThrowAnyException();
  }

  @Test
  void valid_message_maps_to_heartbeat_command() {
    String payload = """
        {"device_id":"gw_1","timestamp":"2026-06-25T10:30:00Z","status":"ONLINE",
         "firmware_version":"1.2.0","memory_usage_pct":43,"cpu_usage_pct":21,"wifi_rssi":-58}
        """;
    MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));

    listener.messageArrived("iot/heartbeat/gw_1", message);

    verify(healthService).upsertHeartbeat(org.mockito.ArgumentMatchers.argThat((HeartbeatCommand cmd) ->
        cmd.deviceId().equals("gw_1")
            && cmd.authenticatedDeviceId() == null
            && cmd.memoryUsagePct() == 43
            && cmd.cpuUsagePct() == 21
            && cmd.wifiRssi() == -58));
  }
}
