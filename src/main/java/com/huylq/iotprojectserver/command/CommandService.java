package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.security.Role;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Command lifecycle + operator control plane (System Design §5.5/§5.8, §9 {@code command}
 * module). Owns write access to {@code commands} and {@code actuator_state}. The rule
 * engine (Phase 7) issues through the same {@link #issue} entry point an operator does —
 * there is no parallel manual path.
 */
public interface CommandService {

  Command issue(IssueCommandCmd cmd);

  Command get(String commandId);

  CommandPage list(String targetId, Command.Status status, OffsetDateTime from, OffsetDateTime to,
                   String cursor, int pageSize);

  /**
   * Receipt ack (MQTT {@code iot/command_ack/{device_id}}, {@code status: RECEIVED}) —
   * optional-but-recommended per the device-team spec; a miss just means the command
   * skips straight from {@code PENDING} to a terminal status.
   */
  void handleReceived(String commandId, OffsetDateTime ts);

  /**
   * Terminal ack ({@code SUCCESS}/{@code FAILED}) — reconciles {@code actuator_state
   * .reportedState} on {@code SUCCESS} regardless of whether the {@code commands.status}
   * write itself was accepted (a late ack for an already-{@code TIMEOUT}'d command still
   * tells us real hardware state; device-team spec Flow 11).
   */
  void handleTerminal(String commandId, String deviceId, Command.Status status, OffsetDateTime executedAt);

  List<ActuatorState> actuatorState(String zone, boolean drifted);

  ActuatorState actuatorState(String deviceId);

  /**
   * Issue-time input, decoupled from the wire DTO so this module does not depend on
   * {@code api}. {@code callerRole} drives the routine/safety authorization split and the
   * override permission check; {@code override}/{@code overrideReason} are validated
   * unconditionally (role + non-blank reason) regardless of whether an active safety hold
   * actually exists.
   *
   * <p>{@code actorType} distinguishes a human-operator issue ({@link
   * AuditLog.ActorType#USER}, the only kind Phase 6 had) from a rule-engine issue ({@link
   * AuditLog.ActorType#SYSTEM}, added in Phase 7): a rule already passed its own write-time
   * safety review (grammar validation, `ADMIN`-only authorship), so role-based
   * authorization and the override checks — both meaningless for a non-human caller — are
   * skipped for {@code SYSTEM}, and {@code callerRole} is {@code null} in that case.
   * {@code SYSTEM} issues also audit as {@code COMMAND_ISSUE} only, never {@code
   * MANUAL_COMMAND} — that event specifically means "a human issued this."
   */
  record IssueCommandCmd(
      String targetId,
      String action,
      Map<String, Object> parameters,
      boolean override,
      String overrideReason,
      String callerId,
      Role callerRole,
      AuditLog.ActorType actorType,
      String ip) {
  }
}
