package com.huylq.iotprojectserver.api.dto.rule;

import com.huylq.iotprojectserver.rules.Rule;

/**
 * Rule record (OpenAPI {@code Rule}).
 */
public record RuleDto(
    String ruleId,
    String name,
    boolean enabled,
    String condition,
    String action,
    int priority,
    String createdBy) {

  public static RuleDto from(Rule r) {
    return new RuleDto(r.getRuleId().toString(), r.getName(), Boolean.TRUE.equals(r.getEnabled()),
        r.getCondition(), r.getAction(), r.getPriority(), r.getCreatedBy());
  }
}
