package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.telemetry.SensorLatest;
import com.huylq.iotprojectserver.telemetry.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleConditionEvaluatorTest {

  @Mock private TelemetryService telemetryService;

  private RuleConditionEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new RuleConditionEvaluator(telemetryService);
  }

  private static SensorLatest reading(Double num, Boolean bool) {
    return SensorLatest.builder().sensorId("s1").zone("office_1").sensorType("x")
        .valueNum(num).valueBool(bool).build();
  }

  @Test
  void single_boolean_clause_matches_when_any_sensor_is_true() {
    when(telemetryService.currentStateByZoneAndType("office_1", "smoke"))
        .thenReturn(List.of(reading(null, false), reading(null, true)));

    RuleCondition c = new RuleCondition(
        List.of(new RuleCondition.Clause("office_1", "smoke", RuleCondition.Operator.EQ, true)),
        RuleCondition.Combinator.SINGLE);

    assertThat(evaluator.matches(c)).isTrue();
  }

  @Test
  void single_boolean_clause_does_not_match_when_no_sensor_reports_true() {
    when(telemetryService.currentStateByZoneAndType("office_1", "smoke"))
        .thenReturn(List.of(reading(null, false)));

    RuleCondition c = new RuleCondition(
        List.of(new RuleCondition.Clause("office_1", "smoke", RuleCondition.Operator.EQ, true)),
        RuleCondition.Combinator.SINGLE);

    assertThat(evaluator.matches(c)).isFalse();
  }

  @Test
  void no_reading_at_all_never_matches() {
    when(telemetryService.currentStateByZoneAndType("office_1", "smoke")).thenReturn(List.of());

    RuleCondition c = new RuleCondition(
        List.of(new RuleCondition.Clause("office_1", "smoke", RuleCondition.Operator.EQ, true)),
        RuleCondition.Combinator.SINGLE);

    assertThat(evaluator.matches(c)).isFalse();
  }

  @Test
  void numeric_threshold_operators() {
    when(telemetryService.currentStateByZoneAndType("office_1", "temp")).thenReturn(List.of(reading(31.0, null)));

    assertThat(evaluator.matches(singleNumeric("office_1", "temp", RuleCondition.Operator.GT, 30.0))).isTrue();
    assertThat(evaluator.matches(singleNumeric("office_1", "temp", RuleCondition.Operator.LT, 30.0))).isFalse();
    assertThat(evaluator.matches(singleNumeric("office_1", "temp", RuleCondition.Operator.GE, 31.0))).isTrue();
    assertThat(evaluator.matches(singleNumeric("office_1", "temp", RuleCondition.Operator.LE, 31.0))).isTrue();
    assertThat(evaluator.matches(singleNumeric("office_1", "temp", RuleCondition.Operator.EQ, 31.0))).isTrue();
    assertThat(evaluator.matches(singleNumeric("office_1", "temp", RuleCondition.Operator.NE, 31.0))).isFalse();
  }

  @Test
  void and_combinator_requires_every_clause() {
    when(telemetryService.currentStateByZoneAndType("office_1", "temp")).thenReturn(List.of(reading(31.0, null)));
    when(telemetryService.currentStateByZoneAndType("office_1", "hmid")).thenReturn(List.of(reading(50.0, null)));

    RuleCondition c = new RuleCondition(List.of(
        new RuleCondition.Clause("office_1", "temp", RuleCondition.Operator.GT, 30.0),
        new RuleCondition.Clause("office_1", "hmid", RuleCondition.Operator.GT, 70.0)),
        RuleCondition.Combinator.AND);

    assertThat(evaluator.matches(c)).isFalse();
  }

  @Test
  void or_combinator_requires_any_clause() {
    when(telemetryService.currentStateByZoneAndType("office_1", "smoke")).thenReturn(List.of(reading(null, false)));
    when(telemetryService.currentStateByZoneAndType("office_2", "smoke")).thenReturn(List.of(reading(null, true)));

    RuleCondition c = new RuleCondition(List.of(
        new RuleCondition.Clause("office_1", "smoke", RuleCondition.Operator.EQ, true),
        new RuleCondition.Clause("office_2", "smoke", RuleCondition.Operator.EQ, true)),
        RuleCondition.Combinator.OR);

    assertThat(evaluator.matches(c)).isTrue();
  }

  private static RuleCondition singleNumeric(String zone, String type, RuleCondition.Operator op, double literal) {
    return new RuleCondition(List.of(new RuleCondition.Clause(zone, type, op, literal)), RuleCondition.Combinator.SINGLE);
  }
}
