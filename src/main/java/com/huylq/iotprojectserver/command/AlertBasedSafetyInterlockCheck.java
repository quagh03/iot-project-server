package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.alert.OpenAlertQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Real safety interlock (security gap-remediation plan Phase 1, closing System Design §7's
 * "manual command contradicting an active safety action is rejected"). The signal is
 * deliberately simple and durable: an {@code OPEN} alert of a safety-linked type in the
 * target's zone already <em>is</em> the record that a hazard is unresolved — no separate
 * "is the rule still active" bookkeeping is needed. Only a <em>de-escalating</em> command
 * (moving a safety actuator toward {@code OFF}/{@code STOP}/{@code CLOSED}) can violate the
 * interlock; escalating a safety actuator (e.g. turning an exhaust fan ON) is always safe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "iot.command.safety-interlock.enabled", havingValue = "true", matchIfMissing = true)
class AlertBasedSafetyInterlockCheck implements SafetyInterlockCheck {

  private final OpenAlertQuery openAlerts;
  private final SafetyInterlockProperties props;

  @Override
  public boolean violatesActiveSafety(String targetDeviceId, String zone, String deviceType, String desiredState) {
    if (!ActuatorStates.isDeEscalating(desiredState)) {
      return false;
    }
    List<String> alertTypes = props.alertTypesFor(deviceType);
    if (alertTypes.isEmpty()) {
      return false;
    }
    boolean held = openAlerts.existsOpenAlert(zone, alertTypes);
    if (held) {
      log.debug("Safety interlock held: target={} zone={} deviceType={} desiredState={} alertTypes={}",
          targetDeviceId, zone, deviceType, desiredState, alertTypes);
    }
    return held;
  }
}
