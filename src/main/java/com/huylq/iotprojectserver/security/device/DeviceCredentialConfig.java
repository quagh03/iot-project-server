package com.huylq.iotprojectserver.security.device;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Device-credential settings. The rotation grace window is how long the previous secret
 * keeps working after a {@code :rotate} so a device can update in place without downtime
 * (System Design §7 "rotation grace").
 */
@ConfigurationProperties("iot.device")
public record DeviceCredentialConfig(Duration credentialRotationGrace) {

  public DeviceCredentialConfig {
    if (credentialRotationGrace == null) credentialRotationGrace = Duration.ofHours(24);
  }
}
