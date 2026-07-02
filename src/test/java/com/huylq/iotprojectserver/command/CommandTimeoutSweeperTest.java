package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.security.detection.SecurityDetectionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommandTimeoutSweeperTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private CommandRepository commandRepo;
  @Mock private AuditService audit;
  @Mock private SecurityDetectionService securityDetection;

  private CommandTimeoutSweeper sweeper;

  @BeforeEach
  void setUp() {
    sweeper = new CommandTimeoutSweeper(commandRepo, audit,
        new CommandProperties(Duration.ofSeconds(30), List.of("exhst_fan")), new SimpleMeterRegistry(),
        securityDetection);
    Clocks.setClock(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    Clocks.setClock(Clock.systemUTC());
  }

  private static Command openCommand(String id, String targetId) {
    Device target = Device.builder().deviceId(targetId).category(Device.Category.actuator)
        .deviceType("light").zone("office_1").status(Device.Status.ACTIVE).build();
    return Command.builder().commandId(id).target(target).type("light").action("SET")
        .parameters(Map.of("status", "ON")).status(Command.Status.PENDING).issuedBy("user-1")
        .issuedAt(NOW.minusSeconds(45)).build();
  }

  @Test
  void sweep_marks_open_expired_commands_timeout_and_audits() {
    Command expired = openCommand("CMD_1", "light_1");
    when(commandRepo.findOpenIssuedBefore(NOW.minusSeconds(30))).thenReturn(List.of(expired));
    when(commandRepo.markTerminalIfOpen("CMD_1", Command.Status.TIMEOUT, null)).thenReturn(1);

    sweeper.sweep();

    verify(audit).system(AuditEvent.COMMAND_TIMEOUT, "light_1", Map.of("commandId", "CMD_1"));
  }

  @Test
  void sweep_skips_audit_when_a_concurrent_ack_won_the_race() {
    Command expired = openCommand("CMD_1", "light_1");
    when(commandRepo.findOpenIssuedBefore(NOW.minusSeconds(30))).thenReturn(List.of(expired));
    when(commandRepo.markTerminalIfOpen("CMD_1", Command.Status.TIMEOUT, null)).thenReturn(0);

    sweeper.sweep();

    verify(audit, never()).system(any(), any(), any());
  }

  @Test
  void sweep_is_a_noop_when_nothing_is_open() {
    when(commandRepo.findOpenIssuedBefore(NOW.minusSeconds(30))).thenReturn(List.of());

    sweeper.sweep();

    verify(commandRepo, never()).markTerminalIfOpen(any(), any(), any());
  }
}
