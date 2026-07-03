package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.RegistryService;
import com.huylq.iotprojectserver.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandServiceImplTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private CommandRepository commandRepo;
  @Mock private ActuatorStateRepository actuatorStateRepo;
  @Mock private RegistryService registry;
  @Mock private CommandDispatcher dispatcher;
  @Mock private SafetyInterlockCheck safetyInterlock;
  @Mock private AuditService audit;

  private CommandServiceImpl service;

  @BeforeEach
  void setUp() {
    CommandProperties props = new CommandProperties(Duration.ofSeconds(30), List.of("exhst_fan"));
    service = new CommandServiceImpl(commandRepo, actuatorStateRepo, registry, dispatcher, safetyInterlock,
        audit, JsonMapper.builder().build(), props);
    Clocks.setClock(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    Clocks.setClock(Clock.systemUTC());
  }

  private static Device actuator(String id, String type, Device.Status status) {
    return Device.builder().deviceId(id).category(Device.Category.actuator).deviceType(type)
        .zone("office_1").status(status).build();
  }

  private static CommandService.IssueCommandCmd cmd(String targetId, String action, Map<String, Object> params,
                                                     Role role, boolean override, String reason) {
    return new CommandService.IssueCommandCmd(targetId, action, params, override, reason,
        "user-1", role, AuditLog.ActorType.USER, "127.0.0.1");
  }

  private static CommandService.IssueCommandCmd systemCmd(String targetId, String action, Map<String, Object> params,
                                                           String ruleId) {
    return new CommandService.IssueCommandCmd(targetId, action, params, false, null,
        ruleId, null, AuditLog.ActorType.SYSTEM, null);
  }

  // ---- target resolution ----------------------------------------------------------------

  @Test
  void unknown_target_is_unprocessable() {
    when(registry.find("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.issue(cmd("ghost", "SET", Map.of("status", "ON"), Role.OPERATOR, false, null)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void non_actuator_target_is_unprocessable() {
    Device sensor = Device.builder().deviceId("s_temp_1").category(Device.Category.sensor)
        .deviceType("temp").zone("office_1").status(Device.Status.ACTIVE).build();
    when(registry.find("s_temp_1")).thenReturn(Optional.of(sensor));

    assertThatThrownBy(() -> service.issue(cmd("s_temp_1", "SET", Map.of("status", "ON"), Role.OPERATOR, false, null)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void inactive_actuator_is_unprocessable() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.SUSPENDED)));

    assertThatThrownBy(() -> service.issue(cmd("light_1", "SET", Map.of("status", "ON"), Role.OPERATOR, false, null)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  // ---- role x actuator-class authorization -----------------------------------------------

  @Test
  void technician_cannot_command_safety_actuator() {
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));

    assertThatThrownBy(() -> service.issue(cmd("exhst_1", "SET", Map.of("status", "ON"), Role.TECHNICIAN, false, null)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void technician_can_command_routine_actuator() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));

    service.issue(cmd("light_1", "SET", Map.of("status", "ON"), Role.TECHNICIAN, false, null));

    verify(commandRepo).save(any());
  }

  @Test
  void operator_cannot_turn_off_safety_actuator() {
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));

    assertThatThrownBy(() -> service.issue(cmd("exhst_1", "SET", Map.of("status", "OFF"), Role.OPERATOR, false, null)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void operator_can_turn_on_safety_actuator() {
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));

    service.issue(cmd("exhst_1", "SET", Map.of("status", "ON"), Role.OPERATOR, false, null));

    verify(commandRepo).save(any());
  }

  @Test
  void admin_can_turn_off_safety_actuator() {
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));

    service.issue(cmd("exhst_1", "SET", Map.of("status", "OFF"), Role.ADMIN, false, null));

    verify(commandRepo).save(any());
  }

  @Test
  void dispatch_failure_does_not_fail_the_issue_call() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));
    org.mockito.Mockito.doThrow(new IllegalStateException("MQTT publish failed"))
        .when(dispatcher).dispatch(anyString(), anyString(), anyString(), anyString(), any());

    Command result = service.issue(cmd("light_1", "SET", Map.of("status", "ON"), Role.OPERATOR, false, null));

    assertThat(result.getStatus()).isEqualTo(Command.Status.PENDING);
    verify(commandRepo).save(any());
  }

  // ---- override structural validation -----------------------------------------------------

  @Test
  void override_by_non_super_admin_is_forbidden() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));

    assertThatThrownBy(() -> service.issue(
        cmd("light_1", "SET", Map.of("status", "ON"), Role.ADMIN, true, "reason")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void override_without_reason_is_unprocessable() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));

    assertThatThrownBy(() -> service.issue(
        cmd("light_1", "SET", Map.of("status", "ON"), Role.SUPER_ADMIN, true, "  ")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  // ---- safety interlock ---------------------------------------------------------------------

  @Test
  void active_safety_hold_blocks_admin_without_override() {
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));
    when(safetyInterlock.violatesActiveSafety(eq("exhst_1"), anyString(), anyString(), anyString())).thenReturn(true);

    assertThatThrownBy(() -> service.issue(cmd("exhst_1", "SET", Map.of("status", "OFF"), Role.ADMIN, false, null)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    verify(commandRepo, never()).save(any());
  }

  @Test
  void super_admin_override_bypasses_interlock_and_audits() {
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));
    when(safetyInterlock.violatesActiveSafety(eq("exhst_1"), anyString(), anyString(), anyString())).thenReturn(true);

    service.issue(cmd("exhst_1", "SET", Map.of("status", "OFF"), Role.SUPER_ADMIN, true, "fire drill confirmed safe"));

    verify(commandRepo).save(any());
    verify(audit).user(eq("user-1"), eq(AuditEvent.SAFETY_OVERRIDE), eq("exhst_1"), any(), eq("127.0.0.1"));
  }

  // ---- happy path -----------------------------------------------------------------------

  @Test
  void happy_path_persists_upserts_desired_state_dispatches_and_audits() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));

    Command result = service.issue(cmd("light_1", "SET", Map.of("status", "ON"), Role.OPERATOR, false, null));

    assertThat(result.getStatus()).isEqualTo(Command.Status.PENDING);
    assertThat(result.getType()).isEqualTo("light");
    verify(commandRepo).save(any());
    verify(actuatorStateRepo).upsertDesired(eq("light_1"), eq("ON"), anyString(), anyString(), eq(NOW));
    verify(dispatcher).dispatch(eq("light_1"), anyString(), eq("light"), eq("SET"), any());
    verify(audit).user(eq("user-1"), eq(AuditEvent.COMMAND_ISSUE), eq("light_1"), any(), eq("127.0.0.1"));
    verify(audit).user(eq("user-1"), eq(AuditEvent.MANUAL_COMMAND), eq("light_1"), any(), eq("127.0.0.1"));
    verify(audit, never()).user(anyString(), eq(AuditEvent.SAFETY_OVERRIDE), anyString(), any(), anyString());
  }

  // ---- system (rule-issued) commands -----------------------------------------------------

  @Test
  void system_issue_skips_role_authorization_and_override_checks() {
    // A rule commanding a safety actuator OFF would be forbidden for every human role
    // below ADMIN — a SYSTEM actor must not be role-gated at all.
    when(registry.find("exhst_1")).thenReturn(Optional.of(actuator("exhst_1", "exhst_fan", Device.Status.ACTIVE)));

    Command result = service.issue(systemCmd("exhst_1", "SET", Map.of("status", "OFF"), "rule-123"));

    assertThat(result.getStatus()).isEqualTo(Command.Status.PENDING);
    assertThat(result.getIssuedBy()).isEqualTo("rule-123");
  }

  @Test
  void system_issue_audits_command_issue_only_never_manual_command() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));

    service.issue(systemCmd("light_1", "SET", Map.of("status", "ON"), "rule-123"));

    verify(audit).system(eq(AuditEvent.COMMAND_ISSUE), eq("light_1"), any());
    verify(audit, never()).user(anyString(), eq(AuditEvent.MANUAL_COMMAND), anyString(), any(), anyString());
    verify(audit, never()).user(anyString(), any(), anyString(), any(), anyString());
  }

  // ---- ack correlation --------------------------------------------------------------------

  @Test
  void handleReceived_delegates_to_repo() {
    service.handleReceived("CMD_1", NOW);

    verify(commandRepo).markReceived("CMD_1", NOW);
  }

  @Test
  void handleTerminal_success_updates_reported_state_and_audits() {
    Device target = actuator("light_1", "light", Device.Status.ACTIVE);
    Command command = Command.builder().commandId("CMD_1").target(target).type("light").action("SET")
        .parameters(Map.of("status", "ON")).status(Command.Status.RECEIVED).issuedBy("user-1").issuedAt(NOW).build();
    when(commandRepo.markTerminalIfOpen("CMD_1", Command.Status.SUCCESS, NOW)).thenReturn(1);
    when(commandRepo.findById("CMD_1")).thenReturn(Optional.of(command));

    service.handleTerminal("CMD_1", "light_1", Command.Status.SUCCESS, NOW);

    verify(actuatorStateRepo).upsertReported("light_1", "ON");
    verify(audit).device("light_1", AuditEvent.COMMAND_EXECUTE, "CMD_1", Map.of("status", "SUCCESS"));
  }

  @Test
  void handleTerminal_failed_does_not_touch_reported_state() {
    when(commandRepo.markTerminalIfOpen("CMD_1", Command.Status.FAILED, NOW)).thenReturn(1);

    service.handleTerminal("CMD_1", "light_1", Command.Status.FAILED, NOW);

    verify(actuatorStateRepo, never()).upsertReported(anyString(), anyString());
    verify(commandRepo, never()).findById(anyString());
  }

  @Test
  void handleTerminal_late_ack_still_reconciles_actuator_state() {
    // The status-guarded update lost the race (already TIMEOUT), but a late SUCCESS ack
    // must still reconcile actuator_state (device-team spec Flow 11).
    Device target = actuator("light_1", "light", Device.Status.ACTIVE);
    Command command = Command.builder().commandId("CMD_1").target(target).type("light").action("SET")
        .parameters(Map.of("status", "ON")).status(Command.Status.TIMEOUT).issuedBy("user-1").issuedAt(NOW).build();
    when(commandRepo.markTerminalIfOpen("CMD_1", Command.Status.SUCCESS, NOW)).thenReturn(0);
    when(commandRepo.findById("CMD_1")).thenReturn(Optional.of(command));

    service.handleTerminal("CMD_1", "light_1", Command.Status.SUCCESS, NOW);

    verify(actuatorStateRepo).upsertReported("light_1", "ON");
  }

  // ---- reads ------------------------------------------------------------------------------

  @Test
  void get_throws_not_found_for_unknown_command() {
    when(commandRepo.findById("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get("ghost"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void actuatorState_unknown_device_is_not_found() {
    when(registry.find("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.actuatorState("ghost"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void actuatorState_non_actuator_device_is_not_found() {
    Device sensor = Device.builder().deviceId("s_temp_1").category(Device.Category.sensor)
        .deviceType("temp").zone("office_1").status(Device.Status.ACTIVE).build();
    when(registry.find("s_temp_1")).thenReturn(Optional.of(sensor));

    assertThatThrownBy(() -> service.actuatorState("s_temp_1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void actuatorState_no_row_yet_is_not_found() {
    when(registry.find("light_1")).thenReturn(Optional.of(actuator("light_1", "light", Device.Status.ACTIVE)));
    when(actuatorStateRepo.findById("light_1")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.actuatorState("light_1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void actuatorState_list_delegates_to_repo() {
    when(actuatorStateRepo.findAllFiltered("office_1", true)).thenReturn(List.of());

    List<ActuatorState> result = service.actuatorState("office_1", true);

    assertThat(result).isEmpty();
    verify(actuatorStateRepo).findAllFiltered("office_1", true);
  }
}
