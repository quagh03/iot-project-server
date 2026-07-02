package com.huylq.iotprojectserver.rules;

import java.util.List;
import java.util.Map;

/**
 * Parsed action AST — one or more effects (System Design §5.6, API §9). Each effect is a
 * data record only; nothing here executes anything (that's {@code RuleActionExecutor}).
 */
public record RuleAction(List<Effect> effects) {

  public sealed interface Effect permits CommandEffect, AlertEffect {
  }

  /**
   * {@code command(targetId, action, {key: value, ...})}. {@code parameters}' values are
   * {@link String} (bareword) or {@link Double} (number) — the per-{@code device_type}
   * whitelist itself is enforced downstream by {@code CommandService.issue}, not here.
   */
  public record CommandEffect(String targetId, String action, Map<String, Object> parameters) implements Effect {
  }

  /** {@code alert(type, severity)}. */
  public record AlertEffect(String type, String severity) implements Effect {
  }
}
