package com.huylq.iotprojectserver.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Base for MQTT integration tests — a real Mosquitto broker via Testcontainers, wired
 * to {@code iot.mqtt.broker-url} before the Spring context starts. Started eagerly in a
 * static initializer (rather than JUnit's {@code @Container} lifecycle) since
 * {@code @DynamicPropertySource} methods run before other JUnit extension callbacks —
 * the container must already be listening by then.
 */
@PostgresIntegrationTest
public abstract class AbstractMqttIT {

  protected static final GenericContainer<?> MOSQUITTO = new GenericContainer<>("eclipse-mosquitto:2")
      .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test.conf"), "/mosquitto/config/mosquitto.conf")
      .withExposedPorts(1883)
      .waitingFor(Wait.forListeningPort());

  static {
    MOSQUITTO.start();
  }

  @DynamicPropertySource
  static void mqttProperties(DynamicPropertyRegistry registry) {
    registry.add("iot.mqtt.broker-url",
        () -> "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883));
  }
}
