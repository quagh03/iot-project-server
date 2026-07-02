package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertService;
import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.command.Command;
import com.huylq.iotprojectserver.command.CommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleActionExecutorTest {

  @Mock private CommandService commandService;
  @Mock private AlertService alertService;

  private RuleActionExecutor executor;

  private static Rule rule(UUID id) {
    return Rule.builder().ruleId(id).name("Smoke rule").enabled(true)
        .condition("office_1.smoke == true").action("alert(SMOKE, CRITICAL)").priority(0)
        .createdBy("admin-1").build();
  }

  @BeforeEach
  void setUp() {
    executor = new RuleActionExecutor(commandService, alertService);
  }

  @Test
  void command_effect_issues_as_system_actor_attributed_to_the_rule() {
    UUID ruleId = UUID.randomUUID();
    RuleAction action = new RuleAction(List.of(
        new RuleAction.CommandEffect("act_exhaust_1", "SET", Map.of("status", "ON"))));

    executor.execute(rule(ruleId), action, "office_1", "s_smoke_1");

    verify(commandService).issue(argThat(cmd ->
        cmd.targetId().equals("act_exhaust_1")
            && cmd.action().equals("SET")
            && cmd.parameters().equals(Map.of("status", "ON"))
            && cmd.callerId().equals(ruleId.toString())
            && cmd.callerRole() == null
            && cmd.actorType() == AuditLog.ActorType.SYSTEM
            && !cmd.override()));
  }

  @Test
  void alert_effect_raises_with_trigger_context_and_a_generated_message() {
    UUID ruleId = UUID.randomUUID();
    RuleAction action = new RuleAction(List.of(new RuleAction.AlertEffect("SMOKE", "CRITICAL")));

    executor.execute(rule(ruleId), action, "office_1", "s_smoke_1");

    verify(alertService).raise(eq("SMOKE"), eq(Alert.Severity.CRITICAL), eq("office_1"), eq("s_smoke_1"), any());
  }

  @Test
  void one_failing_effect_does_not_prevent_the_next_effect_from_running() {
    UUID ruleId = UUID.randomUUID();
    when(commandService.issue(any())).thenThrow(new RuntimeException("target decommissioned"));
    RuleAction action = new RuleAction(List.of(
        new RuleAction.CommandEffect("act_exhaust_1", "SET", Map.of("status", "ON")),
        new RuleAction.AlertEffect("SMOKE", "CRITICAL")));

    executor.execute(rule(ruleId), action, "office_1", "s_smoke_1");

    verify(alertService).raise(eq("SMOKE"), eq(Alert.Severity.CRITICAL), any(), any(), any());
  }

  @Test
  void multiple_effects_all_execute() {
    UUID ruleId = UUID.randomUUID();
    RuleAction action = new RuleAction(List.of(
        new RuleAction.CommandEffect("act_exhaust_1", "SET", Map.of("status", "ON")),
        new RuleAction.AlertEffect("SMOKE", "CRITICAL")));
    when(commandService.issue(any())).thenReturn(
        Command.builder().commandId("CMD_1").status(Command.Status.PENDING).build());

    executor.execute(rule(ruleId), action, "office_1", "s_smoke_1");

    verify(commandService).issue(any());
    verify(alertService).raise(any(), any(), any(), any(), any());
  }
}
