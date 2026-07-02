package com.huylq.iotprojectserver.mqtt;

import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.health.DeviceHealth;
import com.huylq.iotprojectserver.health.DeviceHealthRepository;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.support.AbstractMqttIT;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
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
 * End-to-end heartbeat + presence against a real broker (Testcontainers Mosquitto) —
 * mirrors {@code TelemetryMqttIngestIT}. Covers what a mocked-listener unit test can't:
 * a heartbeat published over the wire lands in {@code device_health}, and a broker-fired
 * Last Will & Testament (ungraceful disconnect) flips the device {@code OFFLINE}.
 */
class HeartbeatMqttIngestIT extends AbstractMqttIT {

  @Autowired DeviceRepository deviceRepo;
  @Autowired SensorRepository sensorRepo;
  @Autowired DeviceHealthRepository healthRepo;
  @Autowired CommandRepository commandRepo;
  @Autowired MqttClientLifecycle appLifecycle;

  private MqttClient deviceClient;

  @BeforeEach
  void seed() throws Exception {
    commandRepo.deleteAll();
    healthRepo.deleteAll();
    sensorRepo.deleteAll();
    deviceRepo.deleteAll(deviceRepo.findAll().stream()
        .filter(d -> d.getParentGateway() != null).toList());
    deviceRepo.deleteAll();

    deviceRepo.save(Device.builder()
        .deviceId("gw_1").category(Device.Category.gateway).deviceType("gateway")
        .zone("office_1").status(Device.Status.ACTIVE).protocols(new String[]{"mqtt"}).build());

    // The app's own MQTT client must be connected and subscribed before we publish —
    // otherwise the broker has no subscription to deliver (or queue) the message against.
    await().atMost(Duration.ofSeconds(20)).until(appLifecycle::isReady);
  }

  @AfterEach
  void disconnectDeviceClient() throws Exception {
    if (deviceClient != null && deviceClient.isConnected()) {
      deviceClient.disconnect();
    }
  }

  @Test
  void heartbeat_published_over_mqtt_upserts_device_health() throws Exception {
    deviceClient = new MqttClient(
        "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883),
        "test-device-" + UUID.randomUUID(), new MemoryPersistence());
    deviceClient.connect();

    String payload = """
        {"device_id":"gw_1","timestamp":"2026-06-25T10:30:00Z","status":"ONLINE",
         "firmware_version":"1.2.0","memory_usage_pct":43,"cpu_usage_pct":21,"wifi_rssi":-58}
        """;
    deviceClient.publish("iot/heartbeat/gw_1", new MqttMessage(payload.getBytes(StandardCharsets.UTF_8)));

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(healthRepo.findById("gw_1")).isPresent());
    DeviceHealth health = healthRepo.findById("gw_1").orElseThrow();
    assertThat(health.getConnectionStatus()).isEqualTo(DeviceHealth.ConnectionStatus.ONLINE);
    assertThat(health.getMemoryUsagePct()).isEqualTo((short) 43);
    assertThat(health.getWifiRssi()).isEqualTo((short) -58);
  }

  @Test
  void lwt_fires_offline_on_ungraceful_disconnect() throws Exception {
    MqttConnectOptions opts = new MqttConnectOptions();
    opts.setWill("iot/status/gw_1", "{\"device_id\":\"gw_1\",\"status\":\"OFFLINE\"}".getBytes(StandardCharsets.UTF_8),
        1, false);
    deviceClient = new MqttClient(
        "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883),
        "test-device-will-" + UUID.randomUUID(), new MemoryPersistence());
    deviceClient.connect(opts);

    deviceClient.publish("iot/heartbeat/gw_1",
        new MqttMessage("{\"device_id\":\"gw_1\",\"timestamp\":\"2026-06-25T10:30:00Z\",\"status\":\"ONLINE\"}"
            .getBytes(StandardCharsets.UTF_8)));
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(healthRepo.findById("gw_1").orElseThrow().getConnectionStatus())
            .isEqualTo(DeviceHealth.ConnectionStatus.ONLINE));

    // Close the connection without sending a protocol DISCONNECT so the broker treats
    // it as ungraceful and fires the registered will.
    deviceClient.disconnectForcibly(0, 0, false);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(healthRepo.findById("gw_1").orElseThrow().getConnectionStatus())
            .isEqualTo(DeviceHealth.ConnectionStatus.OFFLINE));
  }
}
