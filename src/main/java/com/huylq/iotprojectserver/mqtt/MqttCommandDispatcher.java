package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.command.CommandDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Publish half of the command loop (System Design §5.5/§5.8: {@code PUBLISH
 * iot/command/{device_id}}, QoS 1). Implements the {@code command} module's published
 * {@link CommandDispatcher} seam using the shared MQTT connection.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqttCommandDispatcher implements CommandDispatcher {

  private final MqttClientLifecycle client;
  private final ObjectMapper json;

  @Override
  public void dispatch(String targetDeviceId, String commandId, String deviceType, String action,
                       Map<String, Object> parameters) {
    MqttCommandPayload payload = new MqttCommandPayload(commandId, targetDeviceId, deviceType, action,
        parameters == null ? Map.of() : parameters);
    byte[] bytes = json.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    client.publish(MqttTopics.commandTopic(targetDeviceId), bytes, 1, false);
    log.debug("Dispatched command {} to {} (action={})", commandId, targetDeviceId, action);
  }
}
