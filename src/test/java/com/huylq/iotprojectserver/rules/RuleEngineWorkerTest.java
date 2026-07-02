package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.telemetry.ReadingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives the worker's real background thread against a mocked queue/repo/evaluator —
 * verifies the dispatch logic (zone/type pre-filter, condition gating, priority order,
 * per-rule failure isolation) without a real DB or a real bounded queue.
 */
@ExtendWith(MockitoExtension.class)
class RuleEngineWorkerTest {

  @Mock private RuleEventQueue queue;
  @Mock private RuleRepository ruleRepo;
  @Mock private RuleConditionEvaluator evaluator;
  @Mock private RuleActionExecutor executor;

  private RuleEngineWorker worker;

  @BeforeEach
  void setUp() {
    worker = new RuleEngineWorker(queue, ruleRepo, evaluator, executor);
  }

  @AfterEach
  void tearDown() {
    worker.stop();
  }

  private static Rule rule(String condition, int priority) {
    return Rule.builder().ruleId(UUID.randomUUID()).name("r").enabled(true)
        .condition(condition).action("alert(SMOKE, CRITICAL)").priority(priority).createdBy("admin-1").build();
  }

  private static ReadingEvent event(String zone, String sensorType) {
    return new ReadingEvent("s1", sensorType, null, true, zone, OffsetDateTime.now());
  }

  @Test
  void skips_rules_whose_condition_does_not_reference_the_events_zone_and_type() throws Exception {
    Rule irrelevant = rule("office_2.smoke == true", 0);
    when(queue.poll(anyLong(), any())).thenReturn(event("office_1", "smoke"), (ReadingEvent) null);
    when(ruleRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(irrelevant));

    worker.start();

    await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
        verify(ruleRepo, atLeastOnce()).findByEnabledTrueOrderByPriorityDesc());
    verify(evaluator, never()).matches(any());
    verify(executor, never()).execute(any(), any(), any(), any());
  }

  @Test
  void evaluates_and_executes_a_matching_rule() throws Exception {
    Rule matching = rule("office_1.smoke == true", 0);
    when(queue.poll(anyLong(), any())).thenReturn(event("office_1", "smoke"), (ReadingEvent) null);
    when(ruleRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(matching));
    when(evaluator.matches(any())).thenReturn(true);

    worker.start();

    await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
        verify(executor).execute(eq(matching), any(), eq("office_1"), eq("s1")));
  }

  @Test
  void does_not_execute_when_condition_does_not_match() throws Exception {
    Rule matching = rule("office_1.smoke == true", 0);
    when(queue.poll(anyLong(), any())).thenReturn(event("office_1", "smoke"), (ReadingEvent) null);
    when(ruleRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(matching));
    when(evaluator.matches(any())).thenReturn(false);

    worker.start();

    await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
        verify(evaluator, atLeastOnce()).matches(any()));
    verify(executor, never()).execute(any(), any(), any(), any());
  }

  @Test
  void evaluates_rules_in_priority_order() throws Exception {
    Rule high = rule("office_1.smoke == true", 10);
    Rule low = rule("office_1.smoke == true", 0);
    // findByEnabledTrueOrderByPriorityDesc's own ORDER BY guarantees this order — the
    // worker must preserve it, not re-sort or reverse it.
    when(queue.poll(anyLong(), any())).thenReturn(event("office_1", "smoke"), (ReadingEvent) null);
    when(ruleRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(high, low));
    when(evaluator.matches(any())).thenReturn(true);

    worker.start();

    await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() -> {
      InOrder order = inOrder(executor);
      order.verify(executor).execute(eq(high), any(), any(), any());
      order.verify(executor).execute(eq(low), any(), any(), any());
    });
  }

  @Test
  void one_rules_parse_failure_does_not_block_evaluating_the_others() throws Exception {
    Rule broken = Rule.builder().ruleId(UUID.randomUUID()).name("broken").enabled(true)
        .condition("this is not valid !!").action("alert(SMOKE, CRITICAL)").priority(10).createdBy("admin-1").build();
    Rule healthy = rule("office_1.smoke == true", 0);
    when(queue.poll(anyLong(), any())).thenReturn(event("office_1", "smoke"), (ReadingEvent) null);
    when(ruleRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(broken, healthy));
    when(evaluator.matches(any())).thenReturn(true);

    worker.start();

    await().atMost(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
        verify(executor).execute(eq(healthy), any(), any(), any()));
  }

  private static long anyLong() {
    return org.mockito.ArgumentMatchers.anyLong();
  }
}
