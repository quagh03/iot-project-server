package com.huylq.iotprojectserver.rules;

import java.util.List;
import java.util.UUID;

/**
 * Rule CRUD (API §9, System Design §9 {@code rules} module). Owns write access to {@code
 * rules}. {@code condition}/{@code action} are validated against the restricted grammar
 * ({@link RuleGrammarParser}) on every write — never on read; a stored row is always
 * assumed syntactically valid once persisted.
 */
public interface RuleService {

  List<Rule> list(Boolean enabled, int offset, int limit);

  long count(Boolean enabled);

  Rule create(RuleInputCmd cmd, String callerId, String ip);

  Rule get(UUID ruleId);

  Rule replace(UUID ruleId, RuleInputCmd cmd, String callerId, String ip);

  Rule patch(UUID ruleId, Boolean enabled, Integer priority, String callerId, String ip);

  void delete(UUID ruleId, String callerId, String ip);

  /**
   * Create/replace input, decoupled from the wire DTO so this module does not depend on
   * {@code api}.
   */
  record RuleInputCmd(String name, Boolean enabled, String condition, String action, Integer priority) {
  }
}
