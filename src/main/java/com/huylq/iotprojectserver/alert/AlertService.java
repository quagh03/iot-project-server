package com.huylq.iotprojectserver.alert;

/**
 * Alert module's published interface (System Design §9 {@code alert} module). Owns write
 * access to {@code alerts}.
 *
 * <p>Phase 7 (rule engine) is the first — and, until Phase 8 lands, only — caller: a
 * rule's {@code alert(type, severity)} action effect raises an {@code OPEN} alert here.
 * Phase 8 adds the read/list and explicit acknowledge/resolve transitions on top of this
 * same write path; {@link #raise} itself is already the complete, real contract (not a
 * seam/no-op) since {@code alerts} already exists and raising one is a plain insert.
 */
public interface AlertService {

  /**
   * @param sourceDeviceId nullable — an unresolvable/unknown device id still raises the
   *                        alert (availability over strict validation for a safety signal),
   *                        just without a resolved {@code sourceDevice}.
   */
  Alert raise(String type, Alert.Severity severity, String zone, String sourceDeviceId, String message);
}
