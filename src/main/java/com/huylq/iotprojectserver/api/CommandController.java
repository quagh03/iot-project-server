package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.ListResponse;
import com.huylq.iotprojectserver.api.dto.command.ActuatorStateDto;
import com.huylq.iotprojectserver.api.dto.command.CommandAckDto;
import com.huylq.iotprojectserver.api.dto.command.CommandDto;
import com.huylq.iotprojectserver.api.dto.command.IssueCommandRequest;
import com.huylq.iotprojectserver.command.Command;
import com.huylq.iotprojectserver.command.CommandPage;
import com.huylq.iotprojectserver.command.CommandService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.idempotency.IdempotencyHelper;
import com.huylq.iotprojectserver.common.pagination.CursorPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import com.huylq.iotprojectserver.security.Role;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Command lifecycle + operator control plane (OpenAPI {@code Commands}/{@code Actuator
 * State} tags, System Design §5.5/§5.8). Reads require {@code VIEWER}; issuing requires
 * at least {@code TECHNICIAN} (routine-only — see {@code CommandService} for the finer
 * role x actuator-class split the design's role matrix requires, which {@code
 * @PreAuthorize} alone can't express). Because device JWTs carry no {@code role} claim,
 * {@code hasRole(...)} also enforces devices-ingest-only (T4).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class CommandController {

  private final CommandService commandService;
  private final PaginationConfig pagination;
  private final IdempotencyHelper idempotency;
  private final ObjectMapper json;

  @PostMapping("/commands")
  @PreAuthorize("hasRole('TECHNICIAN')")
  public ResponseEntity<CommandAckDto> issue(@Valid @RequestBody IssueCommandRequest req,
                                             @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                             @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    Role callerRole = callerRole(caller);
    log.info("POST /commands targetId='{}' action={} caller={} role={}",
        req.targetId(), req.action(), caller.getSubject(), callerRole);
    String ip = AuthController.clientIp(http);
    return idempotency.run(idempotencyKey, "POST /v1/commands", json.writeValueAsString(req), CommandAckDto.class,
        () -> {
          Command command = commandService.issue(new CommandService.IssueCommandCmd(
              req.targetId(), req.action(), req.parametersOrEmpty(), req.overrideOrFalse(), req.overrideReason(),
              caller.getSubject(), callerRole, ip));
          return ResponseEntity.accepted()
              .location(URI.create("/api/v1/commands/" + command.getCommandId()))
              .body(CommandAckDto.from(command));
        });
  }

  @GetMapping("/commands")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<PagedResponse<CommandDto>> list(
      @RequestParam(required = false) String targetId,
      @RequestParam(required = false) Command.Status status,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer pageSize) {
    if (from != null && to != null && !to.isAfter(from)) {
      throw ApiException.unprocessable("to must be after from");
    }
    int limit = pagination.clamp(pageSize);
    CommandPage page = commandService.list(targetId, status, from, to, cursor, limit);
    List<CommandDto> items = page.items().stream().map(CommandDto::from).toList();
    CursorPage pageMeta = page.hasMore()
        ? CursorPage.of(page.nextCursor(), items.size())
        : CursorPage.end(items.size());
    return ResponseEntity.ok(PagedResponse.cursor(items, pageMeta));
  }

  @GetMapping("/commands/{commandId}")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<CommandDto> get(@PathVariable String commandId) {
    return ResponseEntity.ok(CommandDto.from(commandService.get(commandId)));
  }

  @GetMapping("/actuator-state")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<ListResponse<ActuatorStateDto>> actuatorState(
      @RequestParam(required = false) String zone,
      @RequestParam(required = false, defaultValue = "false") boolean drifted) {
    List<ActuatorStateDto> items = commandService.actuatorState(zone, drifted).stream()
        .map(ActuatorStateDto::from).toList();
    return ResponseEntity.ok(ListResponse.of(items));
  }

  @GetMapping("/devices/{deviceId}/actuator-state")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<ActuatorStateDto> deviceActuatorState(@PathVariable String deviceId) {
    return ResponseEntity.ok(ActuatorStateDto.from(commandService.actuatorState(deviceId)));
  }

  private static Role callerRole(Jwt jwt) {
    String role = jwt.getClaimAsString("role");
    if (role == null) throw ApiException.forbidden("Missing role on caller token");
    return Role.valueOf(role);
  }
}
