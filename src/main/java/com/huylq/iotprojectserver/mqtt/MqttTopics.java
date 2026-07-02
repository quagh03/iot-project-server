package com.huylq.iotprojectserver.mqtt;

/**
 * MQTT topic-filter constants (System Design §6 topic table). Centralized here so
 * Phase 5/6 additions (heartbeat, command, command-ack, presence) land alongside this
 * one rather than as scattered string literals.
 */
public final class MqttTopics {

  /** {@code iot/telemetry/{zone}/{gateway_id}} — per-gateway suffix for broker-ACL granularity. */
  public static final String TELEMETRY_FILTER = "iot/telemetry/+/+";

  /** {@code iot/heartbeat/{device_id}} — device-published health upsert. */
  public static final String HEARTBEAT_FILTER = "iot/heartbeat/+";

  /** {@code iot/status/{device_id}} — broker-published Last Will & Testament (presence). */
  public static final String STATUS_FILTER = "iot/status/+";

  /** {@code iot/command_ack/{device_id}} — device-published receipt/execution ack. */
  public static final String COMMAND_ACK_FILTER = "iot/command_ack/+";

  private static final String COMMAND_TOPIC_PREFIX = "iot/command/";

  /** {@code iot/command/{device_id}} — app-published command dispatch. */
  public static String commandTopic(String deviceId) {
    return COMMAND_TOPIC_PREFIX + deviceId;
  }

  private MqttTopics() {
  }
}
