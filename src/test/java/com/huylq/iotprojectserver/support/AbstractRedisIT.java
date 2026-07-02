package com.huylq.iotprojectserver.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Base for Redis-backed integration tests — a real Redis via Testcontainers, wired to
 * Spring Boot's native {@code spring.data.redis.*} properties (not the app's own {@code
 * iot.redis.*} block, which only gates which bean implementation loads) before the
 * context starts. Mirrors {@code AbstractMqttIT}: started eagerly in a static initializer
 * since {@code @DynamicPropertySource} runs before other JUnit extension callbacks.
 */
@PostgresIntegrationTest
public abstract class AbstractRedisIT {

  protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
      .withExposedPorts(6379)
      .waitingFor(Wait.forListeningPort());

  static {
    REDIS.start();
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("iot.redis.enabled", () -> "true");
  }
}
