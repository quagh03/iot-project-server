package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.device.CredentialMetadataDto;
import com.huylq.iotprojectserver.api.dto.device.CredentialSecretDto;
import com.huylq.iotprojectserver.api.dto.device.ScopeSetDto;
import com.huylq.iotprojectserver.common.idempotency.IdempotencyHelper;
import com.huylq.iotprojectserver.security.device.DeviceCredentialService;
import com.huylq.iotprojectserver.security.device.DeviceCredentialService.IssuedCredential;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Device credential & scope endpoints (OpenAPI {@code Device Credentials} tag) — all
 * {@code ADMIN}. The client secret is returned <b>once</b> on issue / rotate and never
 * by any read endpoint; {@code GET /credentials} exposes metadata only.
 */
@RestController
@RequestMapping("/api/v1/devices/{deviceId}")
@RequiredArgsConstructor
@Slf4j
public class DeviceCredentialController {

  private final DeviceCredentialService credentials;
  private final IdempotencyHelper idempotency;

  @GetMapping("/credentials")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<CredentialMetadataDto> getMetadata(@PathVariable String deviceId) {
    log.debug("GET /devices/{}/credentials", deviceId);
    DeviceCredentialService.CredentialMetadata meta = credentials.getMetadata(deviceId);
    return ResponseEntity.ok(new CredentialMetadataDto(meta.clientId(), meta.rotatedAt()));
  }

  @PostMapping("/credentials")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<CredentialSecretDto> issue(@PathVariable String deviceId,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                   @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /devices/{}/credentials caller={}", deviceId, caller.getSubject());
    String ip = AuthController.clientIp(http);
    return idempotency.run(idempotencyKey, "POST /v1/devices/" + deviceId + "/credentials", "",
        CredentialSecretDto.class,
        () -> {
          IssuedCredential issued = credentials.issue(deviceId, caller.getSubject(), ip);
          return ResponseEntity.created(URI.create("/api/v1/devices/" + deviceId + "/credentials"))
              .body(toDto(issued));
        });
  }

  @PostMapping("/credentials:rotate")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<CredentialSecretDto> rotate(@PathVariable String deviceId,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                    @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /devices/{}/credentials:rotate caller={}", deviceId, caller.getSubject());
    String ip = AuthController.clientIp(http);
    return idempotency.run(idempotencyKey, "POST /v1/devices/" + deviceId + "/credentials:rotate", "",
        CredentialSecretDto.class,
        () -> ResponseEntity.status(HttpStatus.OK).body(toDto(credentials.rotate(deviceId, caller.getSubject(), ip))));
  }

  @GetMapping("/scopes")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ScopeSetDto> getScopes(@PathVariable String deviceId) {
    log.debug("GET /devices/{}/scopes", deviceId);
    return ResponseEntity.ok(new ScopeSetDto(credentials.getScopes(deviceId)));
  }

  @PutMapping("/scopes")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<ScopeSetDto> replaceScopes(@PathVariable String deviceId,
                                                   @Valid @RequestBody ScopeSetDto req,
                                                   @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("PUT /devices/{}/scopes scopes={} caller={}", deviceId, req.scopes(), caller.getSubject());
    var stored = credentials.replaceScopes(deviceId, req.scopes(), caller.getSubject(),
        AuthController.clientIp(http));
    return ResponseEntity.ok(new ScopeSetDto(stored));
  }

  private static CredentialSecretDto toDto(IssuedCredential c) {
    return new CredentialSecretDto(c.clientId(), c.clientSecret(), c.rotatedAt(), c.graceExpiresAt());
  }
}
