package com.huylq.iotprojectserver.audit;


import java.util.Map;

/**
 * Append-only audit writer. Phase 1 ships the writer; the query API arrives in Phase 9.
 *
 * <p>All security-relevant events must call one of these methods: login, device
 * register/delete, credential rotation, rule change, command execution, role change.
 */
public interface AuditService {

    /** Generic append — caller specifies actor type explicitly. */
    void append(String actor, AuditLog.ActorType actorType, String event,
                String target, Map<String, Object> detail, String ip);

    default void user(String userId, String event, String target,
                      Map<String, Object> detail, String ip) {
        append(userId, AuditLog.ActorType.USER, event, target, detail, ip);
    }

    default void device(String deviceId, String event, String target,
                        Map<String, Object> detail) {
        append(deviceId, AuditLog.ActorType.DEVICE, event, target, detail, null);
    }

    default void system(String event, String target, Map<String, Object> detail) {
        append("system", AuditLog.ActorType.SYSTEM, event, target, detail, null);
    }
}
