package com.huylq.iotprojectserver.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKeyId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

  @Id
  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private UUID idempotencyKey;

  @Id
  @Column(name = "endpoint", nullable = false, updatable = false, length = 128)
  private String endpoint;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "response_status")
  private Short responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", columnDefinition = "jsonb")
  private Map<String, Object> responseBody;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;
}
