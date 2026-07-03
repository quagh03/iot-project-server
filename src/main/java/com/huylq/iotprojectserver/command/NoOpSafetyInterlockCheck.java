package com.huylq.iotprojectserver.command;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Explicit opt-out of the real interlock ({@link AlertBasedSafetyInterlockCheck}) for
 * isolated tests or environments that don't want the {@code alert}-module dependency.
 * Active only when {@code iot.command.safety-interlock.enabled=false} — the app default
 * is the real check, mirroring how {@code iot.redis.enabled} switches the denylist backend.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "iot.command.safety-interlock.enabled", havingValue = "false")
class NoOpSafetyInterlockCheck implements SafetyInterlockCheck {

  @Override
  public boolean violatesActiveSafety(String targetDeviceId, String zone, String deviceType, String desiredState) {
    log.trace("Safety-interlock disabled (no-op): target={} zone={} deviceType={}", targetDeviceId, zone, deviceType);
    return false;
  }
}
