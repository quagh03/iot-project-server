package com.huylq.iotprojectserver.command;

import java.util.Map;

/**
 * Safety-interlock seam (System Design §5.8/§7, implementation plan Phase 6 note): "a
 * manual command that contradicts an active safety action... is rejected for everyone
 * below {@code SUPER_ADMIN}." Full enforcement needs the active-safety-state signal from
 * the rule engine (Phase 7) and open alerts (Phase 8); until those land, the only
 * implementation is a no-op ({@link NoOpSafetyInterlockCheck}) so the command-issue path
 * has the documented {@code 409}/override contract wired without a real hold source yet —
 * mirrors the {@code telemetry.RuleEventPublisher} seam.
 */
public interface SafetyInterlockCheck {

  /**
   * True if issuing this command on this target would contradict an active safety
   * rule/alert (e.g. commanding {@code exhaust OFF} while a smoke rule holds it {@code ON}).
   */
  boolean violatesActiveSafety(String targetDeviceId, String action, Map<String, Object> parameters);
}
