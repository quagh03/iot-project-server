package com.huylq.iotprojectserver.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ActuatorStateRepository extends JpaRepository<ActuatorState, String> {

  /**
   * {@code ?zone=} joins {@code devices} (zone isn't stored on the mirror); {@code
   * ?drifted=true} serves off the partial drift index (System Design §5.11) — {@code
   * IS DISTINCT FROM} treats {@code NULL} desired/reported as a real difference, matching
   * the index predicate exactly. {@code JOIN FETCH} avoids N+1 when the caller reads
   * {@code device.zone} per row.
   */
  @Query("""
      SELECT a FROM ActuatorState a JOIN FETCH a.device d
      WHERE (CAST(:zone AS string) IS NULL OR d.zone = :zone)
        AND (:drifted = false OR a.desiredState IS DISTINCT FROM a.reportedState)
      ORDER BY d.zone, a.deviceId
      """)
  List<ActuatorState> findAllFiltered(@Param("zone") String zone, @Param("drifted") boolean drifted);

  /**
   * Upsert on command issue (System Design §5.8) — updates {@code desiredState} +
   * traceability columns, leaves {@code reportedState} untouched.
   */
  @Modifying
  @Query(value = """
      INSERT INTO actuator_state (device_id, desired_state, attributes, last_command_id, commanded_at, updated_at)
      VALUES (:deviceId, :desiredState, CAST(:attributesJson AS jsonb), :commandId, :commandedAt, now())
      ON CONFLICT (device_id) DO UPDATE SET
          desired_state   = EXCLUDED.desired_state,
          attributes      = EXCLUDED.attributes,
          last_command_id = EXCLUDED.last_command_id,
          commanded_at    = EXCLUDED.commanded_at,
          updated_at      = now()
      """, nativeQuery = true)
  int upsertDesired(@Param("deviceId") String deviceId, @Param("desiredState") String desiredState,
                    @Param("attributesJson") String attributesJson, @Param("commandId") String commandId,
                    @Param("commandedAt") OffsetDateTime commandedAt);

  /**
   * Upsert on terminal-success ack (System Design §5.8) — updates only {@code
   * reportedState}; a first-ever row for this device gets the {@code attributes} column
   * default ({@code '{}'}) since it's omitted from the insert list.
   */
  @Modifying
  @Query(value = """
      INSERT INTO actuator_state (device_id, reported_state, updated_at)
      VALUES (:deviceId, :reportedState, now())
      ON CONFLICT (device_id) DO UPDATE SET
          reported_state = EXCLUDED.reported_state,
          updated_at     = now()
      """, nativeQuery = true)
  int upsertReported(@Param("deviceId") String deviceId, @Param("reportedState") String reportedState);
}
