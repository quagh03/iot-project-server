package com.huylq.iotprojectserver.command;

/**
 * Safety-interlock seam (System Design §5.8/§7): "a manual command that contradicts an
 * active safety action... is rejected for everyone below {@code SUPER_ADMIN}." The real
 * signal is an {@code OPEN} alert of a safety-linked type in the target's zone
 * ({@link AlertBasedSafetyInterlockCheck}, security gap-remediation plan Phase 1);
 * {@link NoOpSafetyInterlockCheck} remains available as an explicit opt-out
 * (@code iot.command.safety-interlock.enabled=false}) for isolated unit tests.
 */
public interface SafetyInterlockCheck {

  /**
   * True if issuing a command that would leave the target actuator in {@code
   * desiredState} contradicts an active safety condition — e.g. commanding {@code exhst_fan}
   * to {@code OFF} while an {@code OPEN} {@code SMOKE} alert exists for {@code zone}.
   *
   * @param zone         the target device's zone
   * @param deviceType   the target device's registry {@code device_type}
   * @param desiredState the command's validated desired state (e.g. {@code "OFF"})
   */
  boolean violatesActiveSafety(String targetDeviceId, String zone, String deviceType, String desiredState);
}
