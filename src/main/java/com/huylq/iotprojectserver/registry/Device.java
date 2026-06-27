package com.huylq.iotprojectserver.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

  @Id
  @Column(name = "device_id", nullable = false, length = 64)
  private String deviceId;

  @Enumerated(EnumType.STRING)
  @Column(name = "category", nullable = false, length = 16)
  private Category category;

  @Column(name = "device_type", nullable = false, length = 32)
  private String deviceType;

  @Column(name = "zone", nullable = false, length = 64)
  private String zone;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_gateway_id")
  private Device parentGateway;

  @Column(name = "firmware_version", length = 32)
  private String firmwareVersion;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private Status status = Status.INACTIVE;

  @Builder.Default
  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(name = "protocols", columnDefinition = "text[]", nullable = false)
  private String[] protocols = new String[0];

  @Version
  @Column(name = "version", nullable = false)
  private Integer version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  public enum Category {
    gateway, sensor, actuator
  }

  public enum Status {
    ACTIVE, INACTIVE, SUSPENDED, DECOMMISSIONED
  }
}
