package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.telemetry.ReadingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The bounded in-process queue System Design §5.6 calls for — decouples the telemetry
 * ingest transaction ({@link QueuedRuleEventPublisher}, the producer) from rule
 * evaluation ({@link RuleEngineWorker}, the consumer). Bounded and non-blocking on offer:
 * a full queue drops the oldest-pending reading's rule evaluation rather than ever
 * blocking the ingest path — telemetry itself is already durably persisted before this is
 * reached, so a dropped rule-evaluation opportunity loses no facts, only a firing.
 */
@Component
@Slf4j
class RuleEventQueue {

  private final BlockingQueue<ReadingEvent> queue;
  private final int capacity;

  RuleEventQueue(RuleEngineProperties props) {
    this.capacity = props.queueCapacity();
    this.queue = new LinkedBlockingQueue<>(capacity);
  }

  void offer(ReadingEvent event) {
    if (!queue.offer(event)) {
      log.warn("Rule event queue full (capacity={}) — dropping reading for {}.{}",
          capacity, event.zone(), event.sensorType());
    }
  }

  ReadingEvent poll(long timeout, TimeUnit unit) throws InterruptedException {
    return queue.poll(timeout, unit);
  }
}
