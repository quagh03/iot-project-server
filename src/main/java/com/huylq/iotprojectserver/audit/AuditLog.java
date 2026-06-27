package com.huylq.iotprojectserver.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

@Entity
@Table(name = "audit_logs")
@IdClass(AuditLogId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, insertable = false, updatable = false)
  private Long id;

  @Id
  @Column(name = "ts", nullable = false, updatable = false)
  private OffsetDateTime ts;

  @PrePersist
  void prePersist() {
    if (ts == null) ts = OffsetDateTime.now();
  }

  @Column(name = "actor", nullable = false, length = 64)
  private String actor;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false, length = 8)
  private ActorType actorType;

  @Column(name = "event", nullable = false, length = 64)
  private String event;

  @Column(name = "target", length = 128)
  private String target;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "detail", columnDefinition = "jsonb")
  private Map<String, Object> detail;

  // PostgreSQL INET stored as String
  @Column(name = "ip", columnDefinition = "inet")
  private String ip;

  public enum ActorType {
    USER, DEVICE, SYSTEM
  }
}
