package com.huylq.iotprojectserver.audit;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Append-only audit writer + query (Phase 1 ships the writer; Phase 9 adds the query
 * half — same module, same published interface).
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

  /**
   * Read-only query over the partitioned, append-only {@code audit_logs} table (API §10)
   * — {@code from}/{@code to} are the caller's responsibility to bound; this module does
   * not enforce the mandatory-window rule itself (that's the {@code api} layer's job, same
   * split as {@code telemetry}'s history query).
   */
  AuditPage query(String actor, AuditLog.ActorType actorType, String event, String target,
                 OffsetDateTime from, OffsetDateTime to, String cursor, int pageSize);
}
