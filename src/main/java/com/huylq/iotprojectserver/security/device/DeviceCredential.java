package com.huylq.iotprojectserver.security.device;

import com.huylq.iotprojectserver.registry.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "device_credentials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceCredential {

  @Id
  @Column(name = "device_id", nullable = false, length = 64)
  private String deviceId;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "device_id", nullable = false)
  private Device device;

  @Column(name = "client_id", nullable = false, unique = true, length = 64)
  private String clientId;

  @Column(name = "client_secret_hash", nullable = false, length = 255)
  private String clientSecretHash;

  @Column(name = "previous_secret_hash", length = 255)
  private String previousSecretHash;

  @Column(name = "grace_expires_at")
  private OffsetDateTime graceExpiresAt;

  @Column(name = "rotated_at")
  private OffsetDateTime rotatedAt;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;
}
