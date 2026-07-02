package com.huylq.iotprojectserver.api.dto.audit;

import com.huylq.iotprojectserver.audit.AuditLog;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Audit entry (OpenAPI {@code AuditLog}). {@code id} is opaque on the wire even though
 * it's a {@code Long} internally (API §1: IDs are opaque strings).
 */
public record AuditLogDto(
    String id,
    OffsetDateTime ts,
    String actor,
    AuditLog.ActorType actorType,
    String event,
    String target,
    Map<String, Object> detail,
    String ip) {

  public static AuditLogDto from(AuditLog a) {
    return new AuditLogDto(a.getId().toString(), a.getTs(), a.getActor(), a.getActorType(),
        a.getEvent(), a.getTarget(), a.getDetail(), a.getIp());
  }
}
