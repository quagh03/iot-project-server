package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.command.Command;
import com.huylq.iotprojectserver.command.CommandService;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommandAckMqttListenerTest {

  @Mock private CommandService commandService;

  private CommandAckMqttListener listener;

  @BeforeEach
  void setUp() {
    listener = new CommandAckMqttListener(commandService, JsonMapper.builder().build());
  }

  @Test
  void malformed_json_never_propagates() {
    MqttMessage message = new MqttMessage("{not valid".getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/command_ack/light_1", message))
        .doesNotThrowAnyException();

    verify(commandService, never()).handleReceived(any(), any());
    verify(commandService, never()).handleTerminal(any(), any(), any(), any());
  }

  @Test
  void malformed_topic_never_propagates() {
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_1","status":"RECEIVED"}
        """.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/command_ack", message))
        .doesNotThrowAnyException();

    verify(commandService, never()).handleReceived(any(), any());
  }

  @Test
  void topic_deviceId_not_matching_payload_deviceId_is_dropped_not_thrown() {
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_other","status":"RECEIVED"}
        """.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/command_ack/light_1", message))
        .doesNotThrowAnyException();

    verify(commandService, never()).handleReceived(any(), any());
  }

  @Test
  void service_throwing_never_propagates_out_of_the_callback() {
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(commandService).handleReceived(any(), any());
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_1","status":"RECEIVED"}
        """.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/command_ack/light_1", message))
        .doesNotThrowAnyException();
  }

  @Test
  void received_status_routes_to_handleReceived() {
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_1","status":"RECEIVED"}
        """.getBytes(StandardCharsets.UTF_8));

    listener.messageArrived("iot/command_ack/light_1", message);

    verify(commandService).handleReceived(eq("CMD_1"), any());
  }

  @Test
  void success_status_routes_to_handleTerminal_with_executed_at() {
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_1","status":"SUCCESS","executed_at":"2026-06-25T10:40:00Z"}
        """.getBytes(StandardCharsets.UTF_8));

    listener.messageArrived("iot/command_ack/light_1", message);

    verify(commandService).handleTerminal("CMD_1", "light_1", Command.Status.SUCCESS,
        OffsetDateTime.parse("2026-06-25T10:40:00Z"));
  }

  @Test
  void failed_status_routes_to_handleTerminal() {
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_1","status":"FAILED","executed_at":"2026-06-25T10:40:00Z"}
        """.getBytes(StandardCharsets.UTF_8));

    listener.messageArrived("iot/command_ack/light_1", message);

    verify(commandService).handleTerminal("CMD_1", "light_1", Command.Status.FAILED,
        OffsetDateTime.parse("2026-06-25T10:40:00Z"));
  }

  @Test
  void unrecognized_status_is_dropped_not_thrown() {
    MqttMessage message = new MqttMessage("""
        {"command_id":"CMD_1","device_id":"light_1","status":"UNKNOWN"}
        """.getBytes(StandardCharsets.UTF_8));

    assertThatCode(() -> listener.messageArrived("iot/command_ack/light_1", message))
        .doesNotThrowAnyException();

    verify(commandService, never()).handleTerminal(any(), any(), any(), any());
  }
}
