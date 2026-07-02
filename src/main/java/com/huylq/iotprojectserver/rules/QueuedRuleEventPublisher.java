package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.telemetry.ReadingEvent;
import com.huylq.iotprojectserver.telemetry.RuleEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The real rule hand-off (System Design §5.6) — replaces {@code telemetry
 * .NoOpRuleEventPublisher} now that Phase 7 owns a consumer. Enqueues only; never blocks
 * or throws back into the ingest transaction that calls {@link #publish}.
 */
@Component
@RequiredArgsConstructor
public class QueuedRuleEventPublisher implements RuleEventPublisher {

  private final RuleEventQueue queue;

  @Override
  public void publish(ReadingEvent event) {
    queue.offer(event);
  }
}
