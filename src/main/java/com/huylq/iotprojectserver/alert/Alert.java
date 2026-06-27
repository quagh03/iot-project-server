package com.huylq.iotprojectserver.alert;

import com.huylq.iotprojectserver.registry.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

import java.time.OffsetDateTime;

@Entity
@Table(name = "alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Alert {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, updatable = false)
  private Long id;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 16)
  private Severity severity;

  @Column(name = "zone", length = 64)
  private String zone;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "source_device_id")
  private Device sourceDevice;

  @Column(name = "message", columnDefinition = "text")
  private String message;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status = Status.OPEN;

  @Column(name = "acknowledged_by", length = 64)
  private String acknowledgedBy;

  @Column(name = "acknowledged_at")
  private OffsetDateTime acknowledgedAt;

  @Column(name = "resolved_by", length = 64)
  private String resolvedBy;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Integer version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  public enum Severity {
    INFO, WARNING, CRITICAL
  }

  public enum Status {
    OPEN, ACK, RESOLVED
  }
}
