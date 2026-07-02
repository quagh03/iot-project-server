package com.huylq.iotprojectserver.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface DeviceHealthRepository extends JpaRepository<DeviceHealth, String> {

  List<DeviceHealth> findByConnectionStatus(DeviceHealth.ConnectionStatus status);

  /**
   * Zone online/offline roll-up (API §6 connectivity). Driven from {@code Device}, not
   * {@code DeviceHealth}, with an ad hoc {@code LEFT JOIN} so a device that has never
   * sent a heartbeat still counts — as offline — rather than being silently excluded.
   */
  @Query("""
      SELECT d.zone AS zone,
             SUM(CASE WHEN dh.connectionStatus = 'ONLINE' THEN 1L ELSE 0L END) AS online,
             SUM(CASE WHEN dh.connectionStatus = 'OFFLINE' OR dh IS NULL THEN 1L ELSE 0L END) AS offline,
             COUNT(d) AS total
      FROM Device d LEFT JOIN DeviceHealth dh ON dh.deviceId = d.deviceId
      WHERE (CAST(:zone AS string) IS NULL OR d.zone = :zone)
      GROUP BY d.zone
      ORDER BY d.zone
      """)
  List<ZoneConnectivityRow> rollUpByZone(@Param("zone") String zone);

  /**
   * Upsert the latest health row for a device on every heartbeat.
   */
  @Modifying
  @Query(value = """
      INSERT INTO device_health (device_id, connection_status, last_seen,
                                  memory_usage_pct, cpu_usage_pct, wifi_rssi, updated_at)
      VALUES (:deviceId, :connectionStatus, :lastSeen,
              :memoryUsagePct, :cpuUsagePct, :wifiRssi, now())
      ON CONFLICT (device_id) DO UPDATE SET
          connection_status = EXCLUDED.connection_status,
          last_seen         = EXCLUDED.last_seen,
          memory_usage_pct  = EXCLUDED.memory_usage_pct,
          cpu_usage_pct     = EXCLUDED.cpu_usage_pct,
          wifi_rssi         = EXCLUDED.wifi_rssi,
          updated_at        = now()
      """, nativeQuery = true)
  int upsert(@Param("deviceId") String deviceId,
             @Param("connectionStatus") String connectionStatus,
             @Param("lastSeen") OffsetDateTime lastSeen,
             @Param("memoryUsagePct") Short memoryUsagePct,
             @Param("cpuUsagePct") Short cpuUsagePct,
             @Param("wifiRssi") Short wifiRssi);

  /**
   * Liveness-only touch (a telemetry reading counts as presence) — leaves any
   * previously reported resource metrics untouched, unlike {@link #upsert}.
   */
  @Modifying
  @Query(value = """
      INSERT INTO device_health (device_id, connection_status, last_seen, updated_at)
      VALUES (:deviceId, 'ONLINE', :lastSeen, now())
      ON CONFLICT (device_id) DO UPDATE SET
          connection_status = 'ONLINE',
          last_seen         = EXCLUDED.last_seen,
          updated_at        = now()
      """, nativeQuery = true)
  int touchOnline(@Param("deviceId") String deviceId, @Param("lastSeen") OffsetDateTime lastSeen);

  /**
   * Consumes the broker-published LWT (System Design §6/§8) — flips presence OFFLINE
   * without disturbing the last known resource metrics.
   */
  @Modifying
  @Query(value = """
      INSERT INTO device_health (device_id, connection_status, updated_at)
      VALUES (:deviceId, 'OFFLINE', now())
      ON CONFLICT (device_id) DO UPDATE SET
          connection_status = 'OFFLINE',
          updated_at        = now()
      """, nativeQuery = true)
  int markOffline(@Param("deviceId") String deviceId);

  /**
   * Staleness sweep (defense-in-depth alongside LWT) — flips any device still marked
   * {@code ONLINE} whose {@code last_seen} has aged past the cutoff.
   */
  @Modifying
  @Query(value = """
      UPDATE device_health
      SET connection_status = 'OFFLINE', updated_at = now()
      WHERE connection_status = 'ONLINE' AND last_seen < :cutoff
      """, nativeQuery = true)
  int markStaleOffline(@Param("cutoff") OffsetDateTime cutoff);
}
