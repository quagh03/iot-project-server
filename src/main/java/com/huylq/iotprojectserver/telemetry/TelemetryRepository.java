package com.huylq.iotprojectserver.telemetry;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface TelemetryRepository extends JpaRepository<Telemetry, TelemetryId> {

  /**
   * Keyset page ordered by {@code (ts DESC, id DESC)}. First page passes {@code cursorTs=to}
   * and {@code cursorId=Long.MAX_VALUE} as a sentinel — the range predicate already implies
   * {@code ts < to}, so the cursor predicate degenerates to a no-op on page 1. Caller fetches
   * {@code pageable} sized {@code pageSize + 1} to detect {@code hasMore} without a COUNT query.
   */
  @Query("""
      SELECT t FROM Telemetry t
      WHERE t.sensorId = :sensorId AND t.ts >= :from AND t.ts < :to
        AND (t.ts < :cursorTs OR (t.ts = :cursorTs AND t.id < :cursorId))
      ORDER BY t.ts DESC, t.id DESC
      """)
  List<Telemetry> findBySensorPage(@Param("sensorId") String sensorId,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   @Param("cursorTs") OffsetDateTime cursorTs,
                                   @Param("cursorId") Long cursorId,
                                   Pageable pageable);

  @Query("""
      SELECT t FROM Telemetry t
      WHERE t.zone = :zone AND t.ts >= :from AND t.ts < :to
        AND (t.ts < :cursorTs OR (t.ts = :cursorTs AND t.id < :cursorId))
      ORDER BY t.ts DESC, t.id DESC
      """)
  List<Telemetry> findByZonePage(@Param("zone") String zone,
                                 @Param("from") OffsetDateTime from,
                                 @Param("to") OffsetDateTime to,
                                 @Param("cursorTs") OffsetDateTime cursorTs,
                                 @Param("cursorId") Long cursorId,
                                 Pageable pageable);
}
