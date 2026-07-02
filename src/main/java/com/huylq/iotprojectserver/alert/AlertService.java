package com.huylq.iotprojectserver.alert;

import java.time.OffsetDateTime;

/**
 * Alert module's published interface (System Design §9 {@code alert} module). Owns write
 * access to {@code alerts}.
 *
 * <p>Phase 7 (rule engine) raises alerts via {@link #raise}. Phase 8 adds the read/list
 * and explicit acknowledge/resolve transitions on top of that same write path — status is
 * never a directly writable field, so the audit trail always captures who transitioned
 * what (API §10).
 */
public interface AlertService {

  /**
   * @param sourceDeviceId nullable — an unresolvable/unknown device id still raises the
   *                        alert (availability over strict validation for a safety signal),
   *                        just without a resolved {@code sourceDevice}.
   */
  Alert raise(String type, Alert.Severity severity, String zone, String sourceDeviceId, String message);

  AlertPage list(Alert.Status status, String zone, Alert.Severity severity, OffsetDateTime from,
                OffsetDateTime to, String cursor, int pageSize);

  Alert get(Long alertId);

  /**
   * {@code OPEN -> ACK}. Any other current status (already {@code ACK}, or {@code
   * RESOLVED}) → {@code 409}.
   */
  Alert acknowledge(Long alertId, String callerId, String ip);

  /**
   * {@code OPEN|ACK -> RESOLVED}. Already {@code RESOLVED} → {@code 409}.
   */
  Alert resolve(Long alertId, String callerId, String ip);
}
