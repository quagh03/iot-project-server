package com.huylq.iotprojectserver.audit;

import java.util.Map;

/**
 * Append-only audit writer. Phase 1 ships the writer; the query API arrives in Phase 9.
 *
 * <p>All security-relevant events must call one of these methods: login, device
 * register/delete, credential rotation, rule change, command execution, role change.
 *
 * <p>Event codes are constrained to {@link AuditEvent} — callers cannot pass a raw
 * String, so misspellings show up as compile errors and every event has one
 * documented home.
 */
public interface AuditService {

  /**
   * Generic append — caller specifies actor type explicitly.
   */
  void append(String actor, AuditLog.ActorType actorType, AuditEvent event,
              String target, Map<String, Object> detail, String ip);

  default void user(String userId, AuditEvent event, String target,
                    Map<String, Object> detail, String ip) {
    append(userId, AuditLog.ActorType.USER, event, target, detail, ip);
  }

  default void device(String deviceId, AuditEvent event, String target,
                      Map<String, Object> detail) {
    append(deviceId, AuditLog.ActorType.DEVICE, event, target, detail, null);
  }

  default void system(AuditEvent event, String target, Map<String, Object> detail) {
    append("system", AuditLog.ActorType.SYSTEM, event, target, detail, null);
  }
}
