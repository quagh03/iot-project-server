package com.huylq.iotprojectserver.alert;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.registry.RegistryService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
class AlertServiceImpl implements AlertService, OpenAlertQuery {

  private final AlertRepository repo;
  private final RegistryService registry;
  private final AuditService audit;

  @Override
  @Transactional(readOnly = true)
  public boolean existsOpenAlert(String zone, Collection<String> types) {
    return !types.isEmpty() && repo.existsByZoneAndTypeInAndStatus(zone, types, Alert.Status.OPEN);
  }

  /**
   * {@code REQUIRES_NEW} — a detection-signal alert (Phase 10) is very often raised from
   * inside a caller that's about to throw and roll back (e.g. {@code AuthServiceImpl
   * .login}'s failure path, via {@code SecurityDetectionService}). The alert must survive
   * that rollback the same way {@code AuditServiceImpl.append} already does, for the same
   * reason: an alert about a failed/rejected operation is worthless if it vanishes
   * alongside the very rollback it's reporting on.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Alert raise(String type, Alert.Severity severity, String zone, String sourceDeviceId, String message) {
    Alert alert = Alert.builder()
        .type(type)
        .severity(severity)
        .zone(zone)
        .sourceDevice(sourceDeviceId == null ? null : registry.find(sourceDeviceId).orElse(null))
        .message(message)
        .status(Alert.Status.OPEN)
        .build();
    alert = repo.save(alert);
    log.info("Alert raised: id={} type={} severity={} zone={} sourceDeviceId={}",
        alert.getId(), type, severity, zone, sourceDeviceId);
    return alert;
  }

  @Override
  @Transactional(readOnly = true)
  public AlertPage list(Alert.Status status, String zone, Alert.Severity severity, OffsetDateTime from,
                        OffsetDateTime to, String cursor, int pageSize) {
    AlertCursor c = cursor != null ? AlertCursor.decode(cursor) : null;
    Sort sort = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
    Pageable pageable = PageRequest.of(0, pageSize + 1, sort);
    List<Alert> rows = repo.findAll(filter(status, zone, severity, from, to, c), pageable).getContent();

    boolean hasMore = rows.size() > pageSize;
    List<Alert> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor = null;
    if (hasMore) {
      Alert last = page.get(page.size() - 1);
      nextCursor = new AlertCursor(last.getCreatedAt(), last.getId()).encode();
    }
    return new AlertPage(page, nextCursor, hasMore);
  }

  @Override
  @Transactional(readOnly = true)
  public Alert get(Long alertId) {
    return repo.findById(alertId)
        .orElseThrow(() -> ApiException.notFound("Alert not found: " + alertId));
  }

  @Override
  @Transactional
  public Alert acknowledge(Long alertId, String callerId, String ip) {
    Alert alert = get(alertId);
    if (alert.getStatus() != Alert.Status.OPEN) {
      throw ApiException.invalidLifecycleTransition(
          "Cannot acknowledge alert " + alertId + " in status " + alert.getStatus());
    }
    alert.setStatus(Alert.Status.ACK);
    alert.setAcknowledgedBy(callerId);
    alert.setAcknowledgedAt(Clocks.nowUtc());
    audit.user(callerId, AuditEvent.ALERT_ACKNOWLEDGE, alertId.toString(), Map.of("type", alert.getType()), ip);
    log.info("Alert {} acknowledged by {}", alertId, callerId);
    return alert;
  }

  @Override
  @Transactional
  public Alert resolve(Long alertId, String callerId, String ip) {
    Alert alert = get(alertId);
    if (alert.getStatus() == Alert.Status.RESOLVED) {
      throw ApiException.invalidLifecycleTransition("Alert " + alertId + " is already resolved");
    }
    alert.setStatus(Alert.Status.RESOLVED);
    alert.setResolvedBy(callerId);
    alert.setResolvedAt(Clocks.nowUtc());
    audit.user(callerId, AuditEvent.ALERT_RESOLVE, alertId.toString(), Map.of("type", alert.getType()), ip);
    log.info("Alert {} resolved by {}", alertId, callerId);
    return alert;
  }

  private static Specification<Alert> filter(Alert.Status status, String zone, Alert.Severity severity,
                                             OffsetDateTime from, OffsetDateTime to, AlertCursor cursor) {
    return (root, q, cb) -> {
      List<Predicate> preds = new ArrayList<>();
      if (status != null) preds.add(cb.equal(root.get("status"), status));
      if (zone != null) preds.add(cb.equal(root.get("zone"), zone));
      if (severity != null) preds.add(cb.equal(root.get("severity"), severity));
      if (from != null) preds.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
      if (to != null) preds.add(cb.lessThan(root.get("createdAt"), to));
      if (cursor != null) {
        preds.add(cb.or(
            cb.lessThan(root.get("createdAt"), cursor.createdAt()),
            cb.and(cb.equal(root.get("createdAt"), cursor.createdAt()),
                cb.lessThan(root.get("id"), cursor.id()))));
      }
      return cb.and(preds.toArray(new Predicate[0]));
    };
  }
}
