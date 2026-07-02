package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.health.HealthService;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * As with the other MQTT listeners, an exception must never escape {@code messageArrived}.
 */
@ExtendWith(MockitoExtension.class)
class PresenceMqttListenerTest {

  @Mock private HealthService healthService;

  private PresenceMqttListener listener;

  @BeforeEach
  void setUp() {
    listener = new PresenceMqttListener(healthService);
  }

  @Test
  void malformed_topic_never_propagates() {
    MqttMessage message = new MqttMessage("offline".getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/status", message))
        .doesNotThrowAnyException();

    verify(healthService, never()).markOffline(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void service_throwing_never_propagates_out_of_the_callback() {
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(healthService).markOffline("gw_1");
    MqttMessage message = new MqttMessage("{\"status\":\"OFFLINE\"}".getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/status/gw_1", message))
        .doesNotThrowAnyException();
  }

  @Test
  void any_message_on_the_topic_marks_the_topic_deviceId_offline_regardless_of_payload() {
    MqttMessage message = new MqttMessage("not even json".getBytes(StandardCharsets.UTF_8));

    listener.messageArrived("iot/status/gw_1", message);

    verify(healthService).markOffline("gw_1");
  }
}
