package com.huylq.iotprojectserver.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 7 seam — no real rule-evaluation consumer exists yet. Replace this bean with
 * the bounded-queue publisher once the rule engine lands.
 */
@Slf4j
@Component
class NoOpRuleEventPublisher implements RuleEventPublisher {

  @Override
  public void publish(ReadingEvent event) {
    log.debug("Rule hand-off seam (no-op): sensorId={} sensorType={}", event.sensorId(), event.sensorType());
  }
}
