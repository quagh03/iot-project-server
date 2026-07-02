package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertPage;
import com.huylq.iotprojectserver.alert.AlertService;
import com.huylq.iotprojectserver.api.dto.alert.AlertDto;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.pagination.CursorPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Alert read/list + explicit lifecycle transitions (OpenAPI {@code Alerts} tag, API §10).
 * Reads require {@code VIEWER}; transitions require {@code OPERATOR}. {@code status} is
 * never a directly writable field — {@code :acknowledge}/{@code :resolve} are named
 * actions so the audit trail always captures who transitioned what.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@Slf4j
public class AlertController {

  private final AlertService alertService;
  private final PaginationConfig pagination;

  @GetMapping
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<PagedResponse<AlertDto>> list(
      @RequestParam(required = false) Alert.Status status,
      @RequestParam(required = false) String zone,
      @RequestParam(required = false) Alert.Severity severity,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer pageSize) {
    if (from != null && to != null && !to.isAfter(from)) {
      throw ApiException.unprocessable("to must be after from");
    }
    int limit = pagination.clamp(pageSize);
    AlertPage page = alertService.list(status, zone, severity, from, to, cursor, limit);
    List<AlertDto> items = page.items().stream().map(AlertDto::from).toList();
    CursorPage pageMeta = page.hasMore()
        ? CursorPage.of(page.nextCursor(), items.size())
        : CursorPage.end(items.size());
    return ResponseEntity.ok(PagedResponse.cursor(items, pageMeta));
  }

  @GetMapping("/{alertId}")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<AlertDto> get(@PathVariable Long alertId) {
    return ResponseEntity.ok(AlertDto.from(alertService.get(alertId)));
  }

  @PostMapping("/{alertId}:acknowledge")
  @PreAuthorize("hasRole('OPERATOR')")
  public ResponseEntity<AlertDto> acknowledge(@PathVariable Long alertId, @AuthenticationPrincipal Jwt caller,
                                              HttpServletRequest http) {
    log.info("POST /alerts/{}:acknowledge caller={}", alertId, caller.getSubject());
    Alert alert = alertService.acknowledge(alertId, caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.ok(AlertDto.from(alert));
  }

  @PostMapping("/{alertId}:resolve")
  @PreAuthorize("hasRole('OPERATOR')")
  public ResponseEntity<AlertDto> resolve(@PathVariable Long alertId, @AuthenticationPrincipal Jwt caller,
                                          HttpServletRequest http) {
    log.info("POST /alerts/{}:resolve caller={}", alertId, caller.getSubject());
    Alert alert = alertService.resolve(alertId, caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.ok(AlertDto.from(alert));
  }
}
