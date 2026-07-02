package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertService;
import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.command.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Executes a matched rule's parsed {@link RuleAction} effects against the published
 * {@code command}/{@code alert} interfaces — the rule engine issues through the same
 * {@code CommandService.issue} entry point an operator does (no parallel manual path,
 * System Design §5.8), just with {@link AuditLog.ActorType#SYSTEM}.
 *
 * <p>Each effect is isolated in its own try/catch: one bad effect (e.g. a target device
 * decommissioned since the rule was written) must not stop a rule's other effects, nor
 * crash the worker thread for every other queued reading.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class RuleActionExecutor {

  private final CommandService commandService;
  private final AlertService alertService;

  void execute(Rule rule, RuleAction action, String triggerZone, String triggerSourceDeviceId) {
    for (RuleAction.Effect effect : action.effects()) {
      try {
        dispatch(rule, effect, triggerZone, triggerSourceDeviceId);
      } catch (Exception e) {
        log.error("Rule {} ('{}') effect {} failed: {}", rule.getRuleId(), rule.getName(), effect, e.getMessage(), e);
      }
    }
  }

  private void dispatch(Rule rule, RuleAction.Effect effect, String triggerZone, String triggerSourceDeviceId) {
    switch (effect) {
      case RuleAction.CommandEffect ce -> commandService.issue(new CommandService.IssueCommandCmd(
          ce.targetId(), ce.action(), ce.parameters(), false, null,
          rule.getRuleId().toString(), null, AuditLog.ActorType.SYSTEM, null));
      case RuleAction.AlertEffect ae -> alertService.raise(ae.type(), Alert.Severity.valueOf(ae.severity()),
          triggerZone, triggerSourceDeviceId, "Rule '" + rule.getName() + "' triggered");
    }
  }
}
