package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.support.AbstractMqttIT;
import com.huylq.iotprojectserver.telemetry.SensorLatestRepository;
import com.huylq.iotprojectserver.telemetry.TelemetryRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end MQTT ingest against a real broker (Testcontainers Mosquitto) — the DoD
 * lines that a unit test with a mocked client can't cover: a reading published over the
 * wire lands in {@code telemetry}/{@code sensor_latest}, and a persistent session
 * (cleanSession=false) redelivers a QoS-1 message published while the app is disconnected.
 */
class TelemetryMqttIngestIT extends AbstractMqttIT {

  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired TelemetryRepository telemetryRepo;
  @Autowired SensorLatestRepository sensorLatestRepo;
  @Autowired MqttClientLifecycle appLifecycle;

  private MqttClient deviceClient;

  @BeforeEach
  void seed() throws Exception {
    telemetryRepo.deleteAll();
    sensorLatestRepo.deleteAll();
    sensorRepo.deleteAll();
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();

    Device gateway = deviceRepo.save(Device.builder()
        .deviceId("gw_1").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());
    sensorRepo.save(Sensor.builder().sensorId("s_temp_1").gateway(gateway).type("temp").zone("office_1").build());

    // The app's own MQTT client must be connected and subscribed before we publish —
    // otherwise the broker has no subscription to deliver (or queue) the message against.
    await().atMost(Duration.ofSeconds(20)).until(appLifecycle::isReady);

    deviceClient = new MqttClient(
        "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883),
        "test-device-" + UUID.randomUUID(), new MemoryPersistence());
    deviceClient.connect();
  }

  @AfterEach
  void disconnectDeviceClient() throws Exception {
    if (deviceClient != null && deviceClient.isConnected()) {
      deviceClient.disconnect();
    }
  }

  @Test
  void reading_published_over_mqtt_lands_in_telemetry_and_sensor_latest() throws Exception {
    String payload = """
        {"timestamp":"2026-06-25T10:30:00Z","zone":"office_1","gateway_id":"gw_1",
         "sensors":[{"id":"s_temp_1","type":"temp","value":22.4,"unit":"C"}]}
        """;
    deviceClient.publish("iot/telemetry/office_1/gw_1",
        new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(telemetryRepo.count()).isEqualTo(1));
    assertThat(sensorLatestRepo.findById("s_temp_1")).isPresent();
    assertThat(sensorLatestRepo.findById("s_temp_1").get().getValueNum()).isEqualTo(22.4);
  }

  @Test
  void persistent_session_redelivers_qos1_message_published_while_app_is_disconnected() throws Exception {
    appLifecycle.stop();
    // Give the broker a moment to process the disconnect before publishing.
    await().atMost(Duration.ofSeconds(5)).until(() -> !appLifecycle.isReady());

    String payload = """
        {"timestamp":"2026-06-25T10:31:00Z","zone":"office_1","gateway_id":"gw_1",
         "sensors":[{"id":"s_temp_1","type":"temp","value":25.1,"unit":"C"}]}
        """;
    deviceClient.publish("iot/telemetry/office_1/gw_1", payload.getBytes(StandardCharsets.UTF_8), 1, false);

    appLifecycle.start();
    await().atMost(Duration.ofSeconds(20)).until(appLifecycle::isReady);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(sensorLatestRepo.findById("s_temp_1")).isPresent());
    assertThat(sensorLatestRepo.findById("s_temp_1").get().getValueNum()).isEqualTo(25.1);
  }
}
