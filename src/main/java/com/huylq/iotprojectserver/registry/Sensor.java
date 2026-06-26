package com.huylq.iotprojectserver.registry;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "sensors")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Sensor {

    @Id
    @Column(name = "sensor_id", nullable = false, length = 64)
    private String sensorId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gateway_id", nullable = false)
    private Device gateway;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "zone", nullable = false, length = 64)
    private String zone;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
