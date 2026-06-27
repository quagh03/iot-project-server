package com.huylq.iotprojectserver.health;

import com.huylq.iotprojectserver.registry.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "device_health")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceHealth {

  @Id
  @Column(name = "device_id", nullable = false, length = 64)
  private String deviceId;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "device_id", nullable = false)
  private Device device;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "connection_status", nullable = false, length = 8)
  private ConnectionStatus connectionStatus = ConnectionStatus.OFFLINE;

  @Column(name = "last_seen")
  private OffsetDateTime lastSeen;

  @Column(name = "memory_usage_pct")
  private Short memoryUsagePct;

  @Column(name = "cpu_usage_pct")
  private Short cpuUsagePct;

  @Column(name = "wifi_rssi")
  private Short wifiRssi;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public enum ConnectionStatus {
    ONLINE, OFFLINE
  }
}
