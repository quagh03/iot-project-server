package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class RuleServiceImpl implements RuleService {

  private final RuleRepository ruleRepo;
  private final AuditService audit;

  @Override
  @Transactional(readOnly = true)
  public List<Rule> list(Boolean enabled, int offset, int limit) {
    Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
    Pageable pageable = PageRequest.of(offset / Math.max(1, limit), limit, sort);
    Page<Rule> page = enabled != null ? ruleRepo.findByEnabled(enabled, pageable) : ruleRepo.findAll(pageable);
    return page.getContent();
  }

  @Override
  @Transactional(readOnly = true)
  public long count(Boolean enabled) {
    return enabled != null ? ruleRepo.countByEnabled(enabled) : ruleRepo.count();
  }

  @Override
  @Transactional
  public Rule create(RuleInputCmd cmd, String callerId, String ip) {
    validate(cmd.condition(), cmd.action());
    Rule rule = Rule.builder()
        .name(cmd.name())
        .enabled(cmd.enabled() == null ? Boolean.TRUE : cmd.enabled())
        .condition(cmd.condition())
        .action(cmd.action())
        .priority(cmd.priority() == null ? 0 : cmd.priority())
        .createdBy(callerId)
        .build();
    rule = ruleRepo.save(rule);
    audit.user(callerId, AuditEvent.RULE_CREATE, rule.getRuleId().toString(), Map.of("name", rule.getName()), ip);
    log.info("Rule '{}' ({}) created by {}", rule.getName(), rule.getRuleId(), callerId);
    return rule;
  }

  @Override
  @Transactional(readOnly = true)
  public Rule get(UUID ruleId) {
    return ruleRepo.findById(ruleId)
        .orElseThrow(() -> ApiException.notFound("Rule not found: " + ruleId));
  }

  @Override
  @Transactional
  public Rule replace(UUID ruleId, RuleInputCmd cmd, String callerId, String ip) {
    Rule rule = get(ruleId);
    validate(cmd.condition(), cmd.action());
    rule.setName(cmd.name());
    rule.setEnabled(cmd.enabled() == null ? Boolean.TRUE : cmd.enabled());
    rule.setCondition(cmd.condition());
    rule.setAction(cmd.action());
    rule.setPriority(cmd.priority() == null ? 0 : cmd.priority());
    audit.user(callerId, AuditEvent.RULE_UPDATE, ruleId.toString(), Map.of("name", rule.getName()), ip);
    log.info("Rule {} replaced by {}", ruleId, callerId);
    return rule;
  }

  @Override
  @Transactional
  public Rule patch(UUID ruleId, Boolean enabled, Integer priority, String callerId, String ip) {
    Rule rule = get(ruleId);
    if (enabled != null) rule.setEnabled(enabled);
    if (priority != null) rule.setPriority(priority);
    audit.user(callerId, AuditEvent.RULE_PATCH, ruleId.toString(),
        Map.of("enabled", String.valueOf(rule.getEnabled()), "priority", String.valueOf(rule.getPriority())), ip);
    log.info("Rule {} patched by {} (enabled={}, priority={})", ruleId, callerId, enabled, priority);
    return rule;
  }

  @Override
  @Transactional
  public void delete(UUID ruleId, String callerId, String ip) {
    Rule rule = get(ruleId);
    ruleRepo.delete(rule);
    audit.user(callerId, AuditEvent.RULE_DELETE, ruleId.toString(), Map.of("name", rule.getName()), ip);
    log.info("Rule {} deleted by {}", ruleId, callerId);
  }

  /**
   * Write-time validation (API §9) — parses {@code condition}/{@code action} against the
   * restricted grammar ({@link RuleGrammarParser}) and additionally checks any {@code
   * alert(...)} effect's severity is a real {@link Alert.Severity}; both throw {@code 422}
   * with the offending token on failure. Never called from a read path — a persisted row
   * is assumed valid.
   */
  private static void validate(String condition, String action) {
    RuleGrammarParser.parseCondition(condition);
    RuleAction parsedAction = RuleGrammarParser.parseAction(action);
    for (RuleAction.Effect effect : parsedAction.effects()) {
      if (effect instanceof RuleAction.AlertEffect ae) {
        try {
          Alert.Severity.valueOf(ae.severity());
        } catch (IllegalArgumentException e) {
          throw ApiException.unprocessable("Unknown alert severity: " + ae.severity());
        }
      }
    }
  }
}
