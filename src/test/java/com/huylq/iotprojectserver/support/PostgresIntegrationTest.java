package com.huylq.iotprojectserver.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed annotation for tests that need a real Postgres 17 container and MockMvc.
 *
 * <p>The {@code test} profile sets {@code spring.datasource.url=jdbc:tc:postgresql:17:///iot_test};
 * Testcontainers' JDBC driver auto-starts a container on first connection.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public @interface PostgresIntegrationTest {
}
