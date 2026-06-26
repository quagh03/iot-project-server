package com.huylq.iotprojectserver.telemetry;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "sensor_latest")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SensorLatest {

    @Id
    @Column(name = "sensor_id", nullable = false, length = 64)
    private String sensorId;

    @Column(name = "zone", nullable = false, length = 64)
    private String zone;

    @Column(name = "sensor_type", nullable = false, length = 32)
    private String sensorType;

    @Column(name = "value_num")
    private Double valueNum;

    @Column(name = "value_bool")
    private Boolean valueBool;

    @Column(name = "unit", length = 16)
    private String unit;

    @Column(name = "ts", nullable = false)
    private OffsetDateTime ts;
}
