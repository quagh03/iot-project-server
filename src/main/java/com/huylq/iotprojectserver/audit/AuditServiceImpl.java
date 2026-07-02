package com.huylq.iotprojectserver.audit;

import com.huylq.iotprojectserver.common.time.Clocks;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
class AuditServiceImpl implements AuditService {

  private final AuditLogRepository repo;

  /**
   * Writes in its own transaction so a rolled-back business operation still leaves an
   * audit trail of what was attempted (failed login, denied rotation, etc.).
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void append(String actor, AuditLog.ActorType actorType, AuditEvent event,
                     String target, Map<String, Object> detail, String ip) {
    AuditLog row = AuditLog.builder()
        .ts(Clocks.nowUtc())
        .actor(actor)
        .actorType(actorType)
        .event(event.code())
        .target(target)
        .detail(detail)
        .ip(ip)
        .build();
    repo.save(row);
  }

  @Override
  @Transactional(readOnly = true)
  public AuditPage query(String actor, AuditLog.ActorType actorType, String event, String target,
                        OffsetDateTime from, OffsetDateTime to, String cursor, int pageSize) {
    AuditCursor c = cursor != null ? AuditCursor.decode(cursor) : null;
    Sort sort = Sort.by(Sort.Direction.DESC, "ts").and(Sort.by(Sort.Direction.DESC, "id"));
    Pageable pageable = PageRequest.of(0, pageSize + 1, sort);
    List<AuditLog> rows = repo.findAll(filter(actor, actorType, event, target, from, to, c), pageable).getContent();

    boolean hasMore = rows.size() > pageSize;
    List<AuditLog> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor = null;
    if (hasMore) {
      AuditLog last = page.get(page.size() - 1);
      nextCursor = new AuditCursor(last.getTs(), last.getId()).encode();
    }
    return new AuditPage(page, nextCursor, hasMore);
  }

  private static Specification<AuditLog> filter(String actor, AuditLog.ActorType actorType, String event,
                                                String target, OffsetDateTime from, OffsetDateTime to,
                                                AuditCursor cursor) {
    return (root, q, cb) -> {
      List<Predicate> preds = new ArrayList<>();
      if (actor != null) preds.add(cb.equal(root.get("actor"), actor));
      if (actorType != null) preds.add(cb.equal(root.get("actorType"), actorType));
      if (event != null) preds.add(cb.equal(root.get("event"), event));
      if (target != null) preds.add(cb.equal(root.get("target"), target));
      if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("ts"), from));
      if (to != null) preds.add(cb.lessThan(root.get("ts"), to));
      if (cursor != null) {
        preds.add(cb.or(
            cb.lessThan(root.get("ts"), cursor.ts()),
            cb.and(cb.equal(root.get("ts"), cursor.ts()),
                cb.lessThan(root.get("id"), cursor.id()))));
      }
      return cb.and(preds.toArray(new Predicate[0]));
    };
  }
}
