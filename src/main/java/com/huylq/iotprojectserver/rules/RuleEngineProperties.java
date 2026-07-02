package com.huylq.iotprojectserver.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rule-engine queue sizing (System Design §5.6: "in-process bounded queue + worker(s)").
 * No capacity is pinned by the design docs — conservative default; at "tens of msgs/s"
 * (system design assumption #2) with a slow-poll worker this is a generous multi-second
 * buffer, not a tight backpressure valve.
 */
@ConfigurationProperties("iot.rules")
public record RuleEngineProperties(int queueCapacity) {

  public RuleEngineProperties {
    if (queueCapacity <= 0) queueCapacity = 1000;
  }
}
