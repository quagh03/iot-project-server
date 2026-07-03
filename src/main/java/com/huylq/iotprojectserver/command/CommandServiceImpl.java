package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.command.CommandParameterValidator.ValidatedCommand;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.RegistryService;
import com.huylq.iotprojectserver.security.Role;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class CommandServiceImpl implements CommandService {

  private final CommandRepository commandRepo;
  private final ActuatorStateRepository actuatorStateRepo;
  private final RegistryService registry;
  private final CommandDispatcher dispatcher;
  private final SafetyInterlockCheck safetyInterlock;
  private final AuditService audit;
  private final ObjectMapper json;
  private final CommandProperties props;

  @Override
  @Transactional
  public Command issue(IssueCommandCmd cmd) {
    Device target = resolveActuator(cmd.targetId());
    ValidatedCommand validated = CommandParameterValidator.validate(target.getDeviceType(), cmd.action(), cmd.parameters());

    boolean manual = cmd.actorType() == AuditLog.ActorType.USER;
    if (manual) {
      authorizeRoleAndActuatorClass(cmd.callerRole(), target, validated);
      validateOverrideRequest(cmd.callerRole(), cmd.override(), cmd.overrideReason());
    }

    String commandId = "CMD_" + UUID.randomUUID();
    boolean overriding = checkSafetyInterlock(cmd, target, validated, commandId);

    OffsetDateTime now = Clocks.nowUtc();
    Command command = Command.builder()
        .commandId(commandId)
        .target(target)
        .type(target.getDeviceType()) // registry-derived, not the caller-supplied type — never trust client input over a validated internal source
        .action(cmd.action())
        .parameters(cmd.parameters() == null ? Map.of() : cmd.parameters())
        .status(Command.Status.PENDING)
        .issuedBy(cmd.callerId())
        .issuedAt(now)
        .build();
    commandRepo.save(command);

    actuatorStateRepo.upsertDesired(target.getDeviceId(), validated.desiredState(),
        json.writeValueAsString(validated.attributes()), commandId, now);

    // A broker outage must not fail the HTTP request (§8 "MQTT is one ingest/dispatch
    // path, not the only thing keeping the API up") — the command stays PENDING and the
    // timeout sweeper surfaces the failure as TIMEOUT rather than silence (§7 command-
    // suppression detection).
    try {
      dispatcher.dispatch(target.getDeviceId(), commandId, target.getDeviceType(), cmd.action(), cmd.parameters());
    } catch (RuntimeException e) {
      log.error("Failed to dispatch command {} to {} — it will time out via the sweeper: {}",
          commandId, target.getDeviceId(), e.getMessage());
    }

    if (manual) {
      audit.user(cmd.callerId(), AuditEvent.COMMAND_ISSUE, target.getDeviceId(),
          Map.of("commandId", commandId, "action", cmd.action()), cmd.ip());
      audit.user(cmd.callerId(), AuditEvent.MANUAL_COMMAND, target.getDeviceId(),
          Map.of("commandId", commandId, "action", cmd.action()), cmd.ip());
      if (overriding) {
        audit.user(cmd.callerId(), AuditEvent.SAFETY_OVERRIDE, target.getDeviceId(),
            Map.of("commandId", commandId, "action", cmd.action(), "overrideReason", cmd.overrideReason()), cmd.ip());
      }
    } else {
      audit.system(AuditEvent.COMMAND_ISSUE, target.getDeviceId(),
          Map.of("commandId", commandId, "action", cmd.action(), "issuedBy", cmd.callerId()));
    }
    log.info("Command {} issued: target={} action={} caller={} override={}",
        commandId, target.getDeviceId(), cmd.action(), cmd.callerId(), overriding);
    return command;
  }

  @Override
  @Transactional(readOnly = true)
  public Command get(String commandId) {
    return commandRepo.findById(commandId)
        .orElseThrow(() -> ApiException.notFound("Command not found: " + commandId));
  }

  @Override
  @Transactional(readOnly = true)
  public CommandPage list(String targetId, Command.Status status, OffsetDateTime from, OffsetDateTime to,
                          String cursor, int pageSize) {
    CommandCursor c = cursor != null ? CommandCursor.decode(cursor) : null;
    Sort sort = Sort.by(Sort.Direction.DESC, "issuedAt").and(Sort.by(Sort.Direction.DESC, "commandId"));
    Pageable pageable = PageRequest.of(0, pageSize + 1, sort);
    List<Command> rows = commandRepo.findAll(filter(targetId, status, from, to, c), pageable).getContent();

    boolean hasMore = rows.size() > pageSize;
    List<Command> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor = null;
    if (hasMore) {
      Command last = page.get(page.size() - 1);
      nextCursor = new CommandCursor(last.getIssuedAt(), last.getCommandId()).encode();
    }
    return new CommandPage(page, nextCursor, hasMore);
  }

  @Override
  @Transactional
  public void handleReceived(String commandId, OffsetDateTime ts) {
    int updated = commandRepo.markReceived(commandId, ts);
    if (updated == 0) {
      log.debug("Ignoring RECEIVED ack for command {} (already past PENDING, or unknown)", commandId);
    }
  }

  @Override
  @Transactional
  public void handleTerminal(String commandId, String deviceId, Command.Status status, OffsetDateTime executedAt) {
    int updated = commandRepo.markTerminalIfOpen(commandId, status, executedAt);
    if (updated == 0) {
      log.info("Late/duplicate terminal ack for command {} (status={}) — already terminal", commandId, status);
    }
    audit.device(deviceId, AuditEvent.COMMAND_EXECUTE, commandId, Map.of("status", status.name()));

    // Reconcile actual hardware state on SUCCESS regardless of the guard outcome above —
    // a late ack still tells us the truth about the device (device-team spec Flow 11).
    if (status == Command.Status.SUCCESS) {
      commandRepo.findById(commandId).ifPresent(c -> {
        ValidatedCommand validated = CommandParameterValidator.validate(
            c.getTarget().getDeviceType(), c.getAction(), c.getParameters());
        actuatorStateRepo.upsertReported(deviceId, validated.desiredState());
      });
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<ActuatorState> actuatorState(String zone, boolean drifted) {
    return actuatorStateRepo.findAllFiltered(zone, drifted);
  }

  @Override
  @Transactional(readOnly = true)
  public ActuatorState actuatorState(String deviceId) {
    Device device = registry.find(deviceId)
        .orElseThrow(() -> ApiException.notFound("Device not found: " + deviceId));
    if (device.getCategory() != Device.Category.actuator) {
      throw ApiException.notFound("Device is not an actuator: " + deviceId);
    }
    return actuatorStateRepo.findById(deviceId)
        .orElseThrow(() -> ApiException.notFound("No actuator-state for device " + deviceId));
  }

  /**
   * Target validation (§7 input validation, API §8): must resolve to an {@code ACTIVE}
   * actuator. Unknown target / wrong category / non-{@code ACTIVE} status are all
   * {@code 422} — the OpenAPI contract for {@code POST /commands} has no {@code 404}.
   */
  private Device resolveActuator(String targetId) {
    Device device = registry.find(targetId)
        .orElseThrow(() -> ApiException.unprocessable("Unknown targetId: " + targetId));
    if (device.getCategory() != Device.Category.actuator) {
      throw ApiException.unprocessable("targetId is not an actuator: " + targetId);
    }
    if (device.getStatus() != Device.Status.ACTIVE) {
      throw ApiException.unprocessable("targetId is not ACTIVE: " + targetId);
    }
    return device;
  }

  /**
   * Role x actuator-class authorization (System Design "Operator control authorization"):
   * {@code VIEWER} never reaches here (denied at {@code @PreAuthorize}); {@code TECHNICIAN}
   * may drive routine actuators only; {@code OPERATOR} may drive routine actuators and may
   * turn a safety actuator ON/escalate but not OFF/de-escalate; {@code ADMIN}/{@code
   * SUPER_ADMIN} are unrestricted by actuator class. Zone scoping is intentionally global
   * per role (no {@code user_zone_grants}) per the project's adopted decision.
   */
  private void authorizeRoleAndActuatorClass(Role callerRole, Device target, ValidatedCommand validated) {
    boolean safety = props.safetyDeviceTypes().contains(target.getDeviceType());
    switch (callerRole) {
      case VIEWER -> throw ApiException.forbidden("VIEWER may not issue commands");
      case TECHNICIAN -> {
        if (safety) throw ApiException.forbidden("TECHNICIAN may not command a safety actuator");
      }
      case OPERATOR -> {
        if (safety && ActuatorStates.isDeEscalating(validated.desiredState())) {
          throw ApiException.forbidden("OPERATOR may only turn a safety actuator ON/escalate");
        }
      }
      case ADMIN, SUPER_ADMIN -> {
        // unrestricted by actuator class
      }
    }
  }

  /**
   * Structural validation of the override request (API §8) — runs unconditionally,
   * independent of whether an active safety hold actually exists: a lower role setting
   * {@code override=true} is {@code 403}; {@code override=true} without a reason is
   * {@code 422}.
   */
  private static void validateOverrideRequest(Role callerRole, boolean override, String overrideReason) {
    if (!override) return;
    if (callerRole != Role.SUPER_ADMIN) {
      throw ApiException.forbidden("Only SUPER_ADMIN may set override=true");
    }
    if (overrideReason == null || overrideReason.isBlank()) {
      throw ApiException.unprocessable("overrideReason is required when override=true");
    }
  }

  /**
   * @return true if an active safety hold existed and was successfully overridden.
   */
  private boolean checkSafetyInterlock(IssueCommandCmd cmd, Device target, ValidatedCommand validated, String commandId) {
    boolean held = safetyInterlock.violatesActiveSafety(
        target.getDeviceId(), target.getZone(), target.getDeviceType(), validated.desiredState());
    if (!held) return false;
    if (cmd.callerRole() == Role.SUPER_ADMIN && cmd.override()) {
      log.warn("SUPER_ADMIN {} overriding active safety hold on {} (commandId={})",
          cmd.callerId(), target.getDeviceId(), commandId);
      return true;
    }
    throw ApiException.safetyInterlock(
        "Command contradicts an active safety action on " + target.getDeviceId());
  }

  private static Specification<Command> filter(String targetId, Command.Status status,
                                                OffsetDateTime from, OffsetDateTime to, CommandCursor cursor) {
    return (root, q, cb) -> {
      List<Predicate> preds = new ArrayList<>();
      if (targetId != null) preds.add(cb.equal(root.get("target").get("deviceId"), targetId));
      if (status != null) preds.add(cb.equal(root.get("status"), status));
      if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), from));
      if (to != null) preds.add(cb.lessThan(root.get("issuedAt"), to));
      if (cursor != null) {
        preds.add(cb.or(
            cb.lessThan(root.get("issuedAt"), cursor.issuedAt()),
            cb.and(cb.equal(root.get("issuedAt"), cursor.issuedAt()),
                cb.lessThan(root.get("commandId"), cursor.commandId()))));
      }
      return cb.and(preds.toArray(new Predicate[0]));
    };
  }
}
