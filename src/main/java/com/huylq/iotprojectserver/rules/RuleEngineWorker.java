package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.telemetry.ReadingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The async worker System Design §5.6 calls for — drains {@link RuleEventQueue} on its
 * own thread so a slow/complex rule or command dispatch can never back up telemetry
 * ingestion. Mirrors {@code mqtt.MqttClientLifecycle}'s {@link SmartLifecycle} shape for
 * managed start/stop.
 *
 * <p>Re-parses each enabled rule's {@code condition}/{@code action} on every evaluation
 * rather than caching an AST — simplest correct thing at the expected scale (tens of
 * rules, tens of readings/s per system design assumption #2); revisit with a
 * cache-invalidated-on-write AST store if the rule count or ingest rate grows enough for
 * repeated parsing to show up as real cost.
 */
@Slf4j
@Component
class RuleEngineWorker implements SmartLifecycle {

  private final RuleEventQueue queue;
  private final RuleRepository ruleRepo;
  private final RuleConditionEvaluator evaluator;
  private final RuleActionExecutor executor;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread thread;

  RuleEngineWorker(RuleEventQueue queue, RuleRepository ruleRepo, RuleConditionEvaluator evaluator,
                   RuleActionExecutor executor) {
    this.queue = queue;
    this.ruleRepo = ruleRepo;
    this.evaluator = evaluator;
    this.executor = executor;
  }

  @Override
  public void start() {
    running.set(true);
    thread = new Thread(this::runLoop, "rule-engine-worker");
    thread.setDaemon(true);
    thread.start();
  }

  @Override
  public void stop() {
    running.set(false);
    if (thread != null) thread.interrupt();
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  private void runLoop() {
    while (running.get()) {
      try {
        ReadingEvent event = queue.poll(1, TimeUnit.SECONDS);
        if (event != null) evaluate(event);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (Exception e) {
        // A bug in one reading's evaluation must not kill the worker thread for every
        // reading after it.
        log.error("Rule engine worker error: {}", e.getMessage(), e);
      }
    }
  }

  private void evaluate(ReadingEvent event) {
    List<Rule> rules = ruleRepo.findByEnabledTrueOrderByPriorityDesc();
    for (Rule rule : rules) {
      try {
        RuleCondition condition = RuleGrammarParser.parseCondition(rule.getCondition());
        if (!referencesZoneAndType(condition, event.zone(), event.sensorType())) continue;
        if (!evaluator.matches(condition)) continue;
        RuleAction action = RuleGrammarParser.parseAction(rule.getAction());
        executor.execute(rule, action, event.zone(), event.sensorId());
      } catch (Exception e) {
        log.error("Failed to evaluate rule {} ('{}'): {}", rule.getRuleId(), rule.getName(), e.getMessage(), e);
      }
    }
  }

  /**
   * Cheap pre-filter: skip evaluating (and querying {@code sensor_latest} for) a rule
   * whose condition doesn't reference this event's {@code zone.sensorType} at all — no
   * clause of that rule could have just changed.
   */
  private static boolean referencesZoneAndType(RuleCondition condition, String zone, String sensorType) {
    return condition.clauses().stream()
        .anyMatch(c -> c.zone().equals(zone) && c.sensorType().equals(sensorType));
  }
}
