package com.huylq.iotprojectserver.rules;

import java.util.List;

/**
 * Parsed condition AST (System Design §5.6 restricted grammar) — a flat list of {@code
 * zone.sensorType op literal} clauses combined by a single combinator. Mixing {@code &&}
 * and {@code ||} in one condition is deliberately not supported (no operator-precedence
 * ambiguity to reason about, no parentheses to parse) — write two rules instead.
 */
public record RuleCondition(List<Clause> clauses, Combinator combinator) {

  public enum Combinator {
    /** Exactly one clause — {@code combinator} is irrelevant. */
    SINGLE,
    AND,
    OR
  }

  public enum Operator {
    EQ, NE, GT, LT, GE, LE
  }

  /**
   * {@code literal} is a {@link Boolean} (compared against {@code valueBool}, {@code EQ}/
   * {@code NE} only) or a {@link Double} (compared against {@code valueNum}, any operator).
   */
  public record Clause(String zone, String sensorType, Operator operator, Object literal) {
  }
}
