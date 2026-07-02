package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.command.ActuatorStateRepository;
import com.huylq.iotprojectserver.command.Command;
import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.command.CommandService;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.security.Role;
import com.huylq.iotprojectserver.support.AbstractMqttIT;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end command dispatch + ack correlation against a real broker (Testcontainers
 * Mosquitto) — mirrors {@code TelemetryMqttIngestIT}, but exercises the direction Phase 6
 * adds: the app is the MQTT *publisher* on issue, and the *subscriber* on ack.
 */
class CommandMqttIT extends AbstractMqttIT {

  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired CommandService commandService;
  @Autowired CommandRepository commandRepo;
  @Autowired ActuatorStateRepository actuatorStateRepo;
  @Autowired MqttClientLifecycle appLifecycle;

  private MqttClient deviceClient;
  private final LinkedBlockingQueue<MqttMessage> received = new LinkedBlockingQueue<>();

  @BeforeEach
  void seed() throws Exception {
    actuatorStateRepo.deleteAll();
    commandRepo.deleteAll();
    sensorRepo.deleteAll();
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();

    deviceRepo.save(Device.builder()
        .deviceId("light_1").category(Device.Category.actuator).deviceType("light")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());

    await().atMost(Duration.ofSeconds(20)).until(appLifecycle::isReady);

    deviceClient = new MqttClient(
        "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883),
        "test-actuator-" + UUID.randomUUID(), new MemoryPersistence());
    deviceClient.connect();
    deviceClient.setCallback(new MqttCallback() {
      @Override
      public void connectionLost(Throwable cause) {
      }

      @Override
      public void messageArrived(String topic, MqttMessage message) {
        received.add(message);
      }

      @Override
      public void deliveryComplete(IMqttDeliveryToken token) {
      }
    });
    deviceClient.subscribe("iot/command/light_1", 1);
  }

  @AfterEach
  void disconnectDeviceClient() throws Exception {
    if (deviceClient != null && deviceClient.isConnected()) {
      deviceClient.disconnect();
    }
  }

  @Test
  void issued_command_is_published_and_terminal_ack_settles_lifecycle_and_actuator_state() throws Exception {
    Command command = commandService.issue(new CommandService.IssueCommandCmd(
        "light_1", "SET", Map.of("status", "ON"), false, null, "user-1", Role.OPERATOR, "127.0.0.1"));

    MqttMessage delivered = received.poll(10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(delivered).isNotNull();
    String payload = new String(delivered.getPayload(), StandardCharsets.UTF_8);
    assertThat(payload).contains("\"command_id\":\"" + command.getCommandId() + "\"")
        .contains("\"target_id\":\"light_1\"")
        .contains("\"type\":\"light\"");

    // Ack #1 — receipt.
    deviceClient.publish("iot/command_ack/light_1", ("""
        {"command_id":"%s","device_id":"light_1","status":"RECEIVED"}
        """.formatted(command.getCommandId())).getBytes(StandardCharsets.UTF_8), 1, false);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(commandRepo.findById(command.getCommandId()).orElseThrow().getStatus())
            .isEqualTo(Command.Status.RECEIVED));

    // Ack #2 — terminal outcome.
    deviceClient.publish("iot/command_ack/light_1", ("""
        {"command_id":"%s","device_id":"light_1","status":"SUCCESS","executed_at":"2026-06-25T10:40:00Z"}
        """.formatted(command.getCommandId())).getBytes(StandardCharsets.UTF_8), 1, false);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(commandRepo.findById(command.getCommandId()).orElseThrow().getStatus())
            .isEqualTo(Command.Status.SUCCESS));
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(actuatorStateRepo.findById("light_1").orElseThrow().getReportedState())
            .isEqualTo("ON"));
  }
}
