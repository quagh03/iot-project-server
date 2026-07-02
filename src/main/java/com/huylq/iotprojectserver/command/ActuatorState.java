package com.huylq.iotprojectserver.command;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Control-plane mirror (V3 {@code actuator_state}, System Design §5.11) — one row per
 * actuator holding desired-vs-reported state for the dashboard toggle UI, kept off the
 * {@code commands} history table. {@code lastCommandId} is a plain scalar column (not a
 * JPA association to {@link com.huylq.iotprojectserver.command.Command}) since reads of
 * this mirror never need to load the command row — the DB-level FK still enforces
 * referential integrity independent of how the ORM maps the column.
 */
@Entity
@Table(name = "actuator_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActuatorState {

  @Id
  @Column(name = "device_id", nullable = false, length = 64)
  private String deviceId;

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "device_id", nullable = false)
  private Device device;

  @Column(name = "desired_state", length = 32)
  private String desiredState;

  @Column(name = "reported_state", length = 32)
  private String reportedState;

  @Builder.Default
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
  private Map<String, Object> attributes = Map.of();

  @Column(name = "last_command_id", length = 64)
  private String lastCommandId;

  @Column(name = "commanded_at")
  private OffsetDateTime commandedAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
