package com.huylq.iotprojectserver.health;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface DeviceHealthRepository extends JpaRepository<DeviceHealth, String> {

    List<DeviceHealth> findByConnectionStatus(DeviceHealth.ConnectionStatus status);

    /** Upsert the latest health row for a device on every heartbeat. */
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
}
