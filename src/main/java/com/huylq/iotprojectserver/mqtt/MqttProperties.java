package com.huylq.iotprojectserver.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT broker connection settings (System Design §6/§8). {@code brokerUrl}/{@code clientId}
 * are profile-specific (local/test/prod) and have no sane default; the rest default to
 * conservative values so a profile only needs to override what it cares about.
 */
@ConfigurationProperties("iot.mqtt")
public record MqttProperties(
    String brokerUrl,
    String clientId,
    String username,
    String password,
    int connectTimeoutSeconds,
    int keepAliveIntervalSeconds,
    boolean automaticReconnect,
    int maxReconnectDelayMs) {

  public MqttProperties {
    if (connectTimeoutSeconds <= 0) connectTimeoutSeconds = 10;
    if (keepAliveIntervalSeconds <= 0) keepAliveIntervalSeconds = 60;
    if (maxReconnectDelayMs <= 0) maxReconnectDelayMs = 30_000;
  }
}
