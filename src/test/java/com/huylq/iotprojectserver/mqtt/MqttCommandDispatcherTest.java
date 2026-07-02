package com.huylq.iotprojectserver.mqtt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MqttCommandDispatcherTest {

  @Mock private MqttClientLifecycle client;

  private MqttCommandDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = new MqttCommandDispatcher(client, JsonMapper.builder().build());
  }

  @Test
  void dispatch_publishes_to_the_devices_command_topic_at_qos_1() {
    dispatcher.dispatch("light_1", "CMD_1", "light", "SET", Map.of("status", "ON"));

    var captor = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(client).publish(eq("iot/command/light_1"), captor.capture(), eq(1), eq(false));

    String payload = new String(captor.getValue(), StandardCharsets.UTF_8);
    assertThat(payload).contains("\"command_id\":\"CMD_1\"")
        .contains("\"target_id\":\"light_1\"")
        .contains("\"type\":\"light\"")
        .contains("\"action\":\"SET\"");
  }

  @Test
  void dispatch_tolerates_null_parameters() {
    dispatcher.dispatch("light_1", "CMD_1", "light", "SET", null);

    verify(client).publish(eq("iot/command/light_1"), org.mockito.ArgumentMatchers.any(), eq(1), eq(false));
  }
}
