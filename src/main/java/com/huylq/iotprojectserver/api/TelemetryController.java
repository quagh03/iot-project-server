package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.ListResponse;
import com.huylq.iotprojectserver.api.dto.health.HeartbeatRequest;
import com.huylq.iotprojectserver.api.dto.telemetry.CurrentStateDto;
import com.huylq.iotprojectserver.api.dto.telemetry.TelemetryIngestRequest;
import com.huylq.iotprojectserver.api.dto.telemetry.TelemetryReadingDto;
import com.huylq.iotprojectserver.api.dto.telemetry.ZoneConnectivityDto;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.pagination.CursorPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.health.HealthService;
import com.huylq.iotprojectserver.health.HeartbeatCommand;
import com.huylq.iotprojectserver.telemetry.ReadingCommand;
import com.huylq.iotprojectserver.telemetry.TelemetryIngestCommand;
import com.huylq.iotprojectserver.telemetry.TelemetryIngestProperties;
import com.huylq.iotprojectserver.telemetry.TelemetryPage;
import com.huylq.iotprojectserver.telemetry.TelemetryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Telemetry ingest (fallback) + history, and the current-state dashboard hot path
 * (OpenAPI {@code Telemetry}/{@code Current state} tags). See {@code TelemetryService}
 * for the shared MQTT/HTTP ingestion funnel.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class TelemetryController {

  private final TelemetryService telemetryService;
  private final HealthService healthService;
  private final PaginationConfig pagination;
  private final TelemetryIngestProperties ingestProps;

  @PostMapping("/telemetry")
  @PreAuthorize("hasAuthority('SCOPE_telemetry:publish')")
  public ResponseEntity<Void> ingest(@Valid @RequestBody TelemetryIngestRequest req,
                                     @AuthenticationPrincipal Jwt device) {
    log.debug("POST /telemetry gatewayId='{}' zone={} readings={} device={}",
        req.gatewayId(), req.zone(), req.readings().size(), device.getSubject());
    List<ReadingCommand> readings = req.readings().stream()
        .map(r -> new ReadingCommand(r.sensorId(), r.sensorType(), r.valueNum(), r.valueBool(), r.unit(), r.ts()))
        .toList();
    telemetryService.ingest(new TelemetryIngestCommand(req.zone(), req.gatewayId(), readings,
        device.getSubject(), Clocks.nowUtc()));
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/telemetry")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<PagedResponse<TelemetryReadingDto>> history(
      @RequestParam(required = false) String sensorId,
      @RequestParam(required = false) String zone,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer pageSize) {
    if ((sensorId == null) == (zone == null)) {
      throw ApiException.unprocessable("Exactly one of sensorId or zone is required");
    }
    if (from == null || to == null) {
      throw ApiException.unprocessable("Both from and to are required");
    }
    if (!to.isAfter(from)) {
      throw ApiException.unprocessable("to must be after from");
    }
    if (Duration.between(from, to).compareTo(ingestProps.historyMaxWindow()) > 0) {
      throw ApiException.unprocessable("Time window exceeds the maximum of " + ingestProps.historyMaxWindow());
    }

    int limit = pagination.clamp(pageSize);
    TelemetryPage page = telemetryService.queryHistory(sensorId, zone, from, to, cursor, limit);
    List<TelemetryReadingDto> items = page.items().stream().map(TelemetryReadingDto::from).toList();
    CursorPage pageMeta = page.hasMore()
        ? CursorPage.of(page.nextCursor(), items.size())
        : CursorPage.end(items.size());
    return ResponseEntity.ok(PagedResponse.cursor(items, pageMeta));
  }

  @GetMapping("/current-state")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<ListResponse<CurrentStateDto>> currentState(
      @RequestParam(required = false) String zone) {
    List<CurrentStateDto> items = telemetryService.currentState(zone).stream()
        .map(CurrentStateDto::from).toList();
    return ResponseEntity.ok(ListResponse.of(items));
  }

  @GetMapping("/sensors/{sensorId}/latest")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<CurrentStateDto> latest(@PathVariable String sensorId) {
    return ResponseEntity.ok(CurrentStateDto.from(telemetryService.latest(sensorId)));
  }

  @GetMapping("/connectivity")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<ListResponse<ZoneConnectivityDto>> connectivity(
      @RequestParam(required = false) String zone) {
    List<ZoneConnectivityDto> items = healthService.connectivity(zone).stream()
        .map(ZoneConnectivityDto::from).toList();
    return ResponseEntity.ok(ListResponse.of(items));
  }

  @PostMapping("/heartbeat")
  @PreAuthorize("hasAuthority('SCOPE_heartbeat:publish')")
  public ResponseEntity<Void> heartbeat(@Valid @RequestBody HeartbeatRequest req,
                                        @AuthenticationPrincipal Jwt device) {
    log.debug("POST /heartbeat deviceId='{}' device={}", req.deviceId(), device.getSubject());
    healthService.upsertHeartbeat(new HeartbeatCommand(req.deviceId(), device.getSubject(),
        req.memoryUsagePct(), req.cpuUsagePct(), req.wifiRssi(), Clocks.nowUtc()));
    return ResponseEntity.accepted().build();
  }
}
