package com.huylq.iotprojectserver.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface CommandRepository extends JpaRepository<Command, String>, JpaSpecificationExecutor<Command> {

  List<Command> findByTarget_DeviceIdOrderByIssuedAtDesc(String targetId);

  /**
   * Open commands scanned by the timeout sweeper — hits the partial
   * {@code idx_commands_open} index (DB design §5.8).
   */
  @Query("""
      SELECT c FROM Command c
      WHERE c.status IN (
          com.huylq.iotprojectserver.command.Command.Status.PENDING,
          com.huylq.iotprojectserver.command.Command.Status.RECEIVED
      ) AND c.issuedAt < :cutoff
      """)
  List<Command> findOpenIssuedBefore(@Param("cutoff") OffsetDateTime cutoff);

  /**
   * Advances {@code PENDING -> RECEIVED} on the device's receipt ack. Status-guarded so
   * a redelivered/out-of-order ack (QoS-1 at-least-once, §5.5) can't move a command
   * backwards or revive one the sweeper already timed out.
   */
  @Modifying
  @Query("""
      UPDATE Command c SET c.status = com.huylq.iotprojectserver.command.Command.Status.RECEIVED,
                            c.receivedAt = :ts
      WHERE c.commandId = :commandId
        AND c.status = com.huylq.iotprojectserver.command.Command.Status.PENDING
      """)
  int markReceived(@Param("commandId") String commandId, @Param("ts") OffsetDateTime ts);

  /**
   * Advances to a terminal status ({@code SUCCESS}/{@code FAILED} on ack, {@code TIMEOUT}
   * from the sweeper) — guarded so it can't race the other writer (§5.5, DB design §5.8):
   * only fires while the command is still {@code PENDING}/{@code RECEIVED}. A late ack for
   * an already-{@code TIMEOUT}'d command affects zero rows here (harmless — the caller
   * still reconciles {@code actuator_state} separately, per device-team spec Flow 11).
   */
  @Modifying
  @Query("""
      UPDATE Command c SET c.status = :status, c.executedAt = :executedAt
      WHERE c.commandId = :commandId
        AND c.status IN (
            com.huylq.iotprojectserver.command.Command.Status.PENDING,
            com.huylq.iotprojectserver.command.Command.Status.RECEIVED
        )
      """)
  int markTerminalIfOpen(@Param("commandId") String commandId, @Param("status") Command.Status status,
                        @Param("executedAt") OffsetDateTime executedAt);
}
