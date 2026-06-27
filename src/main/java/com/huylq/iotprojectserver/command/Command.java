package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.registry.Device;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "commands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Command {

  @Id
  @Column(name = "command_id", nullable = false, length = 64)
  private String commandId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "target_id", nullable = false)
  private Device target;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Column(name = "action", nullable = false, length = 32)
  private String action;

  @Builder.Default
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "parameters", columnDefinition = "jsonb", nullable = false)
  private Map<String, Object> parameters = Map.of();

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status = Status.PENDING;

  @Column(name = "issued_by", nullable = false, length = 64)
  private String issuedBy;

  @Column(name = "issued_at", nullable = false)
  private OffsetDateTime issuedAt;

  @Column(name = "received_at")
  private OffsetDateTime receivedAt;

  @Column(name = "executed_at")
  private OffsetDateTime executedAt;

  public enum Status {
    PENDING, RECEIVED, SUCCESS, FAILED, TIMEOUT
  }
}
