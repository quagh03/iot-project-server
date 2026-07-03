package com.huylq.iotprojectserver.command;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Safety-interlock configuration (System Design §7 "Operator control authorization &
 * safety interlocks", security gap-remediation plan Phase 1). Maps a safety {@code
 * device_type} to the alert {@code type}s whose presence, {@code OPEN} in the actuator's
 * zone, counts as an active safety hold on that actuator — e.g. an open {@code SMOKE}
 * alert holds an {@code exhst_fan} actuator.
 */
@ConfigurationProperties("iot.command.safety-interlock")
public record SafetyInterlockProperties(Map<String, List<String>> alertTypesByDeviceType) {

  public SafetyInterlockProperties {
    if (alertTypesByDeviceType == null) {
      alertTypesByDeviceType = Map.of("exhst_fan", List.of("SMOKE"));
    }
  }

  List<String> alertTypesFor(String deviceType) {
    return alertTypesByDeviceType.getOrDefault(deviceType, List.of());
  }
}
