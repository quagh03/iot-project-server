package com.huylq.iotprojectserver.mqtt;

/**
 * MQTT topic-filter constants (System Design §6 topic table). Centralized here so
 * Phase 5/6 additions (heartbeat, command, command-ack, presence) land alongside this
 * one rather than as scattered string literals.
 */
public final class MqttTopics {

  /** {@code iot/telemetry/{zone}/{gateway_id}} — per-gateway suffix for broker-ACL granularity. */
  public static final String TELEMETRY_FILTER = "iot/telemetry/+/+";

  private MqttTopics() {
  }
}
