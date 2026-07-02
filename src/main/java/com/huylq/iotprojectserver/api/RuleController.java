package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.rule.RuleDto;
import com.huylq.iotprojectserver.api.dto.rule.RuleInputRequest;
import com.huylq.iotprojectserver.api.dto.rule.RulePatchRequest;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.pagination.OffsetPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import com.huylq.iotprojectserver.rules.Rule;
import com.huylq.iotprojectserver.rules.RuleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Rule CRUD (OpenAPI {@code Rules} tag, API §9). Reads require {@code OPERATOR};
 * mutations require {@code ADMIN}. {@code condition}/{@code action} are validated against
 * the restricted grammar on every write — see {@code RuleService}/{@code
 * RuleGrammarParser} — never `eval`, never on read.
 */
@RestController
@RequestMapping("/api/v1/rules")
@RequiredArgsConstructor
@Slf4j
public class RuleController {

  private final RuleService ruleService;
  private final PaginationConfig pagination;

  @GetMapping
  @PreAuthorize("hasRole('OPERATOR')")
  public ResponseEntity<PagedResponse<RuleDto>> list(
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(required = false) Integer limit) {
    int pageSize = pagination.clamp(limit);
    List<RuleDto> items = ruleService.list(enabled, offset, pageSize).stream().map(RuleDto::from).toList();
    long total = ruleService.count(enabled);
    return ResponseEntity.ok(PagedResponse.offset(items, new OffsetPage(offset, pageSize, total)));
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RuleDto> create(@Valid @RequestBody RuleInputRequest req,
                                        @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /rules name='{}' caller={}", req.name(), caller.getSubject());
    Rule rule = ruleService.create(toCommand(req), caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.created(URI.create("/api/v1/rules/" + rule.getRuleId())).body(RuleDto.from(rule));
  }

  @GetMapping("/{ruleId}")
  @PreAuthorize("hasRole('OPERATOR')")
  public ResponseEntity<RuleDto> get(@PathVariable UUID ruleId) {
    return ResponseEntity.ok(RuleDto.from(ruleService.get(ruleId)));
  }

  @PutMapping("/{ruleId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RuleDto> replace(@PathVariable UUID ruleId, @Valid @RequestBody RuleInputRequest req,
                                         @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("PUT /rules/{} caller={}", ruleId, caller.getSubject());
    Rule rule = ruleService.replace(ruleId, toCommand(req), caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.ok(RuleDto.from(rule));
  }

  @PatchMapping("/{ruleId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<RuleDto> patch(@PathVariable UUID ruleId, @RequestBody RulePatchRequest req,
                                       @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("PATCH /rules/{} caller={}", ruleId, caller.getSubject());
    if (req.isEmpty()) {
      throw ApiException.unprocessable("At least one of enabled, priority is required");
    }
    Rule rule = ruleService.patch(ruleId, req.enabled(), req.priority(), caller.getSubject(),
        AuthController.clientIp(http));
    return ResponseEntity.ok(RuleDto.from(rule));
  }

  @DeleteMapping("/{ruleId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable UUID ruleId, @AuthenticationPrincipal Jwt caller,
                                     HttpServletRequest http) {
    log.info("DELETE /rules/{} caller={}", ruleId, caller.getSubject());
    ruleService.delete(ruleId, caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.noContent().build();
  }

  private static RuleService.RuleInputCmd toCommand(RuleInputRequest req) {
    return new RuleService.RuleInputCmd(req.name(), req.enabled(), req.condition(), req.action(), req.priority());
  }
}
