package com.huylq.iotprojectserver.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface TelemetryRepository extends JpaRepository<Telemetry, TelemetryId> {

    @Query("""
            SELECT t FROM Telemetry t
            WHERE t.sensorId = :sensorId AND t.ts >= :from AND t.ts < :to
            ORDER BY t.ts DESC
            """)
    List<Telemetry> findBySensorInRange(@Param("sensorId") String sensorId,
                                        @Param("from") OffsetDateTime from,
                                        @Param("to") OffsetDateTime to);

    @Query("""
            SELECT t FROM Telemetry t
            WHERE t.zone = :zone AND t.ts >= :from AND t.ts < :to
            ORDER BY t.ts DESC
            """)
    List<Telemetry> findByZoneInRange(@Param("zone") String zone,
                                      @Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to);
}
