package com.huylq.iotprojectserver.telemetry;

/**
 * Rule hand-off seam (System Design §5.6 "persist first, then enqueue for async rule
 * evaluation"). Phase 7 owns the real bounded-queue consumer; until it exists, the
 * only implementation is a no-op ({@link NoOpRuleEventPublisher}) so the ingest path
 * has the documented seam without unused queue infrastructure.
 */
public interface RuleEventPublisher {

  void publish(ReadingEvent event);
}
