package com.huylq.iotprojectserver.audit;

import lombok.*;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class AuditLogId implements Serializable {

    private Long id;
    private OffsetDateTime ts;
}
