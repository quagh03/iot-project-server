package com.huylq.iotprojectserver.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "telemetry")
@IdClass(TelemetryId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Telemetry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false, insertable = false, updatable = false)
  private Long id;

  @Id
  @Column(name = "ts", nullable = false)
  private OffsetDateTime ts;

  @Column(name = "zone", nullable = false, length = 64)
  private String zone;

  @Column(name = "gateway_id", nullable = false, length = 64)
  private String gatewayId;

  @Column(name = "sensor_id", nullable = false, length = 64)
  private String sensorId;

  @Column(name = "sensor_type", nullable = false, length = 32)
  private String sensorType;

  @Column(name = "value_num")
  private Double valueNum;

  @Column(name = "value_bool")
  private Boolean valueBool;

  @Column(name = "unit", length = 16)
  private String unit;
}
