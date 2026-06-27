package com.huylq.iotprojectserver.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface SensorLatestRepository extends JpaRepository<SensorLatest, String> {

  List<SensorLatest> findByZone(String zone);

  /**
   * Upsert the latest reading for a sensor. Guards against out-of-order samples:
   * an older {@code ts} than the row's current {@code ts} is silently dropped.
   */
  @Modifying
  @Query(value = """
      INSERT INTO sensor_latest (sensor_id, zone, sensor_type, value_num, value_bool, unit, ts)
      VALUES (:sensorId, :zone, :sensorType, :valueNum, :valueBool, :unit, :ts)
      ON CONFLICT (sensor_id) DO UPDATE SET
          zone        = EXCLUDED.zone,
          sensor_type = EXCLUDED.sensor_type,
          value_num   = EXCLUDED.value_num,
          value_bool  = EXCLUDED.value_bool,
          unit        = EXCLUDED.unit,
          ts          = EXCLUDED.ts
      WHERE EXCLUDED.ts >= sensor_latest.ts
      """, nativeQuery = true)
  int upsert(@Param("sensorId") String sensorId,
             @Param("zone") String zone,
             @Param("sensorType") String sensorType,
             @Param("valueNum") Double valueNum,
             @Param("valueBool") Boolean valueBool,
             @Param("unit") String unit,
             @Param("ts") OffsetDateTime ts);
}
