package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.audit.AuditLogDto;
import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.audit.AuditPage;
import com.huylq.iotprojectserver.audit.AuditQueryProperties;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.pagination.CursorPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Read-only query over the append-only audit trail (OpenAPI {@code Audit} tag, API §10).
 * There is no create/update/delete endpoint — every module writes {@code audit_logs}
 * internally via {@code AuditService.append}/{@code user}/{@code device}/{@code system}.
 * Bounded {@code from}/{@code to} is required, same rule as {@code telemetry}'s history
 * query, since {@code audit_logs} is partitioned by {@code ts} (non-negotiable invariant
 * #6). Min role {@code ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/audit-logs")
@RequiredArgsConstructor
@Slf4j
public class AuditLogController {

  private final AuditService auditService;
  private final PaginationConfig pagination;
  private final AuditQueryProperties props;

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<PagedResponse<AuditLogDto>> query(
      @RequestParam(required = false) String actor,
      @RequestParam(required = false) AuditLog.ActorType actorType,
      @RequestParam(required = false) String event,
      @RequestParam(required = false) String target,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer pageSize) {
    if (from == null || to == null) {
      throw ApiException.unprocessable("Both from and to are required");
    }
    if (!to.isAfter(from)) {
      throw ApiException.unprocessable("to must be after from");
    }
    if (Duration.between(from, to).compareTo(props.historyMaxWindow()) > 0) {
      throw ApiException.unprocessable("Time window exceeds the maximum of " + props.historyMaxWindow());
    }

    int limit = pagination.clamp(pageSize);
    AuditPage page = auditService.query(actor, actorType, event, target, from, to, cursor, limit);
    List<AuditLogDto> items = page.items().stream().map(AuditLogDto::from).toList();
    CursorPage pageMeta = page.hasMore()
        ? CursorPage.of(page.nextCursor(), items.size())
        : CursorPage.end(items.size());
    return ResponseEntity.ok(PagedResponse.cursor(items, pageMeta));
  }
}
