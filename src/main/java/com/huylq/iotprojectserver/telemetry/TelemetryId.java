package com.huylq.iotprojectserver.telemetry;

import lombok.*;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TelemetryId implements Serializable {

    private Long id;
    private OffsetDateTime ts;
}
