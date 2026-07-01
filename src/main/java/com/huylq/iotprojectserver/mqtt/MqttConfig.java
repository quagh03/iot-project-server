package com.huylq.iotprojectserver.mqtt;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires each module's listener to its topic filter. Phase 5/6 add their own
 * {@code @Bean MqttTopicSubscription} methods here (or in their own package) as
 * heartbeat/command-ack/presence handlers land.
 */
@Configuration
public class MqttConfig {

  @Bean
  MqttTopicSubscription telemetryTopicSubscription(TelemetryMqttListener listener) {
    return new MqttTopicSubscription(MqttTopics.TELEMETRY_FILTER, 1, listener);
  }
}
