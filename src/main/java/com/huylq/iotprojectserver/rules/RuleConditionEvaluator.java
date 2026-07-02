package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.telemetry.SensorLatest;
import com.huylq.iotprojectserver.telemetry.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluates a parsed {@link RuleCondition} against current sensor state ({@code
 * sensor_latest}, via the published {@code telemetry} interface — never a direct
 * repository reach-through). Re-reads state fresh on every evaluation rather than
 * trusting the triggering event's payload, so every clause (including ones for sensors
 * other than the one that triggered evaluation) is evaluated the same way.
 */
@Component
@RequiredArgsConstructor
class RuleConditionEvaluator {

  private final TelemetryService telemetryService;

  boolean matches(RuleCondition condition) {
    return switch (condition.combinator()) {
      case SINGLE -> matchesClause(condition.clauses().get(0));
      case AND -> condition.clauses().stream().allMatch(this::matchesClause);
      case OR -> condition.clauses().stream().anyMatch(this::matchesClause);
    };
  }

  /**
   * "Any sensor of this type in this zone satisfies" — the conservative default for a
   * monitoring/safety system (multiple smoke sensors in a zone: favor a false trigger
   * over missing a hazard). No sensor of that type having ever reported → no match.
   */
  private boolean matchesClause(RuleCondition.Clause clause) {
    List<SensorLatest> readings = telemetryService.currentStateByZoneAndType(clause.zone(), clause.sensorType());
    return readings.stream().anyMatch(r -> satisfies(r, clause));
  }

  private boolean satisfies(SensorLatest reading, RuleCondition.Clause clause) {
    if (clause.literal() instanceof Boolean expected) {
      if (reading.getValueBool() == null) return false;
      boolean actual = reading.getValueBool();
      return clause.operator() == RuleCondition.Operator.EQ ? actual == expected : actual != expected;
    }
    if (clause.literal() instanceof Double expected) {
      if (reading.getValueNum() == null) return false;
      double actual = reading.getValueNum();
      return switch (clause.operator()) {
        case EQ -> actual == expected;
        case NE -> actual != expected;
        case GT -> actual > expected;
        case LT -> actual < expected;
        case GE -> actual >= expected;
        case LE -> actual <= expected;
      };
    }
    return false;
  }
}
