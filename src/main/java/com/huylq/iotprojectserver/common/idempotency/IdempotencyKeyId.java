package com.huylq.iotprojectserver.common.idempotency;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class IdempotencyKeyId implements Serializable {

    private UUID idempotencyKey;
    private String endpoint;
}
