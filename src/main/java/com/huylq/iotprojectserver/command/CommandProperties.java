package com.huylq.iotprojectserver.command;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Command safety-loop configuration (System Design §5.5/§5.8). No ack-timeout value is
 * pinned by the design docs (device-team spec §1.4 flags it "confirm N") — configurable,
 * conservative default. {@code safetyDeviceTypes} is the routine/safety actuator split
 * (System Design "Operator control authorization") — there is no DB column for this, so
 * it's an application-layer classification.
 */
@ConfigurationProperties("iot.command")
public record CommandProperties(Duration ackTimeout, List<String> safetyDeviceTypes) {

  public CommandProperties {
    if (ackTimeout == null) ackTimeout = Duration.ofSeconds(30);
    if (safetyDeviceTypes == null) safetyDeviceTypes = List.of("exhst_fan");
  }
}
