package com.huylq.iotprojectserver.command;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Phase 7/8 seam — no rule engine or alert state exists yet, so no command can be held by
 * an active safety action. Replace this bean once the rule engine (Phase 7) and alert
 * service (Phase 8) exist, wiring their published interfaces here.
 */
@Slf4j
@Component
class NoOpSafetyInterlockCheck implements SafetyInterlockCheck {

  @Override
  public boolean violatesActiveSafety(String targetDeviceId, String action, Map<String, Object> parameters) {
    log.trace("Safety-interlock seam (no-op): target={} action={}", targetDeviceId, action);
    return false;
  }
}
