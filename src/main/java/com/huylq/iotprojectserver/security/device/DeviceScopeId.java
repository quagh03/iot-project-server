package com.huylq.iotprojectserver.security.device;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class DeviceScopeId implements Serializable {

    private String deviceId;
    private String scope;
}
