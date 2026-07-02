package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.command.Command;
import com.huylq.iotprojectserver.command.CommandService;
import com.huylq.iotprojectserver.common.time.Clocks;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Ack half of the command loop (device-team spec §4.3, Flow 9/10/11) — correlates by
 * {@code command_id}, advancing {@code PENDING -> RECEIVED -> SUCCESS/FAILED}. Mirrors
 * {@link TelemetryMqttListener}/{@link HeartbeatMqttListener}: never let an exception
 * escape {@code messageArrived}, cross-check the topic-embedded {@code device_id} against
 * the payload, drop (don't throw) on any shape mismatch.
 *
 * <p>{@code CommandService} is injected {@code @Lazy}: {@code command}'s {@code
 * CommandDispatcher} implementation ({@code MqttCommandDispatcher}) depends on {@link
 * MqttClientLifecycle}, which collects every {@code MqttTopicSubscription} bean
 * (including this listener) in its constructor — without laziness that's an
 * unresolvable circular dependency (commandService -> mqtt dispatch -> topic
 * subscriptions -> this listener -> commandService).
 */
@Slf4j
@Component
public class CommandAckMqttListener implements IMqttMessageListener {

  private final CommandService commandService;
  private final ObjectMapper json;

  public CommandAckMqttListener(@Lazy CommandService commandService, ObjectMapper json) {
    this.commandService = commandService;
    this.json = json;
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    try {
      handle(topic, message);
    } catch (Exception e) {
      log.error("Dropping malformed command-ack message on topic {}: {}", topic, e.getMessage());
    }
  }

  private void handle(String topic, MqttMessage message) {
    String[] segments = topic.split("/");
    if (segments.length != 3 || !"command_ack".equals(segments[1])) {
      log.warn("Unexpected command-ack topic shape: {}", topic);
      return;
    }
    String topicDeviceId = segments[2];

    MqttCommandAckPayload payload = json.readValue(message.getPayload(), MqttCommandAckPayload.class);
    if (!topicDeviceId.equals(payload.deviceId())) {
      log.warn("Command-ack topic/payload deviceId mismatch: topic={} payload={}",
          topicDeviceId, payload.deviceId());
      return;
    }
    if (payload.commandId() == null || payload.status() == null) {
      log.warn("Command-ack missing command_id/status on topic {}", topic);
      return;
    }

    switch (payload.status()) {
      case "RECEIVED" -> commandService.handleReceived(payload.commandId(), Clocks.nowUtc());
      case "SUCCESS" -> handleTerminal(payload, Command.Status.SUCCESS);
      case "FAILED" -> handleTerminal(payload, Command.Status.FAILED);
      default -> log.warn("Unrecognized command-ack status '{}' for command {}", payload.status(), payload.commandId());
    }
  }

  private void handleTerminal(MqttCommandAckPayload payload, Command.Status status) {
    // The terminal ack must carry executed_at per spec; fall back to server-received time
    // rather than dropping a mandatory ack over a missing optional-in-practice field.
    var executedAt = payload.executedAt() != null ? payload.executedAt() : Clocks.nowUtc();
    commandService.handleTerminal(payload.commandId(), payload.deviceId(), status, executedAt);
  }
}
