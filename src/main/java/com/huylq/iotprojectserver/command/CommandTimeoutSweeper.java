package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.security.detection.SecurityDetectionService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Command-suppression detection signal (System Design §5.5/§7 "Availability as a
 * security property" — a dropped MQTT message must surface as a {@code TIMEOUT}, not
 * silence). Scans the partial {@code idx_commands_open} index and status-guards the
 * transition so it can never race a concurrent ack ({@code CommandRepository
 * .markTerminalIfOpen} only fires while still {@code PENDING}/{@code RECEIVED}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandTimeoutSweeper {

  private final CommandRepository commandRepo;
  private final AuditService audit;
  private final CommandProperties props;
  private final MeterRegistry meterRegistry;
  private final SecurityDetectionService securityDetection;

  @Scheduled(fixedDelayString = "PT10S")
  @Transactional
  public void sweep() {
    OffsetDateTime cutoff = Clocks.nowUtc().minus(props.ackTimeout());
    for (Command c : commandRepo.findOpenIssuedBefore(cutoff)) {
      int updated = commandRepo.markTerminalIfOpen(c.getCommandId(), Command.Status.TIMEOUT, null);
      if (updated > 0) {
        audit.system(AuditEvent.COMMAND_TIMEOUT, c.getTarget().getDeviceId(),
            Map.of("commandId", c.getCommandId()));
        // Tagged by device_type (bounded cardinality), never raw deviceId (unbounded —
        // bad Prometheus practice). A rising rate here across many distinct targets is
        // the command-suppression detection signal (§7 T3); see security detection.
        meterRegistry.counter("iot.command.timeouts", "deviceType", c.getTarget().getDeviceType()).increment();
        securityDetection.recordCommandTimeout(c.getTarget().getDeviceId());
        log.warn("Command {} timed out (target={}, issuedAt={})",
            c.getCommandId(), c.getTarget().getDeviceId(), c.getIssuedAt());
      }
    }
  }
}
