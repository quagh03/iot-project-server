package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.ListResponse;
import com.huylq.iotprojectserver.api.dto.device.DeviceDto;
import com.huylq.iotprojectserver.api.dto.device.RegisterDeviceRequest;
import com.huylq.iotprojectserver.api.dto.device.SensorDto;
import com.huylq.iotprojectserver.api.dto.device.UpdateDeviceRequest;
import com.huylq.iotprojectserver.api.dto.health.DeviceHealthDto;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.idempotency.IdempotencyHelper;
import com.huylq.iotprojectserver.common.pagination.OffsetPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import com.huylq.iotprojectserver.health.HealthService;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.RegistryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Device registry & lifecycle endpoints (OpenAPI {@code Devices} tag).
 *
 * <p>Reads require {@code VIEWER}; mutations require {@code ADMIN}. Because device JWTs
 * carry no {@code role} claim, {@code hasRole(...)} also enforces the devices-ingest-only
 * rule (T4) — a device token can never reach these admin/read surfaces.
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
@Slf4j
public class DeviceController {

  private final RegistryService registry;
  private final HealthService healthService;
  private final PaginationConfig pagination;
  private final IdempotencyHelper idempotency;
  private final ObjectMapper json;

  @GetMapping
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<PagedResponse<DeviceDto>> list(@RequestParam(required = false) String zone,
                                                       @RequestParam(required = false) Device.Category category,
                                                       @RequestParam(required = false) String deviceType,
                                                       @RequestParam(required = false) Device.Status status,
                                                       @RequestParam(defaultValue = "0") int offset,
                                                       @RequestParam(required = false) Integer limit) {
    log.debug("GET /devices zone={} category={} deviceType={} status={} offset={} limit={}",
        zone, category, deviceType, status, offset, limit);
    int pageSize = pagination.clamp(limit);
    List<DeviceDto> items = registry.list(zone, category, deviceType, status, offset, pageSize).stream()
        .map(DeviceDto::from).toList();
    long total = registry.count(zone, category, deviceType, status);
    return ResponseEntity.ok(PagedResponse.offset(items, new OffsetPage(offset, pageSize, total)));
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<DeviceDto> register(@Valid @RequestBody RegisterDeviceRequest req,
                                            @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                            @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /devices deviceId='{}' category={} caller={}", req.deviceId(), req.category(), caller.getSubject());
    String ip = AuthController.clientIp(http);
    return idempotency.run(idempotencyKey, "POST /v1/devices", json.writeValueAsString(req), DeviceDto.class,
        () -> {
          Device device = registry.register(toCommand(req), caller.getSubject(), ip);
          DeviceDto dto = DeviceDto.from(device);
          return ResponseEntity.created(URI.create("/api/v1/devices/" + device.getDeviceId())).body(dto);
        });
  }

  @GetMapping("/{deviceId}")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<DeviceDto> get(@PathVariable String deviceId) {
    log.debug("GET /devices/{}", deviceId);
    return ResponseEntity.ok(DeviceDto.from(registry.get(deviceId)));
  }

  @PatchMapping("/{deviceId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<DeviceDto> update(@PathVariable String deviceId,
                                          @Valid @RequestBody UpdateDeviceRequest req,
                                          @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("PATCH /devices/{} caller={}", deviceId, caller.getSubject());
    if (req.isEmpty()) {
      throw ApiException.unprocessable("At least one of zone, deviceType, firmwareVersion is required");
    }
    Device device = registry.update(deviceId, req.zone(), req.deviceType(), req.firmwareVersion(),
        caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.ok(DeviceDto.from(device));
  }

  @GetMapping("/{deviceId}/health")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<DeviceHealthDto> health(@PathVariable String deviceId) {
    log.debug("GET /devices/{}/health", deviceId);
    return ResponseEntity.ok(DeviceHealthDto.from(healthService.getHealth(deviceId)));
  }

  @GetMapping("/{deviceId}/sensors")
  @PreAuthorize("hasRole('VIEWER')")
  public ResponseEntity<ListResponse<SensorDto>> listSensors(@PathVariable String deviceId) {
    log.debug("GET /devices/{}/sensors", deviceId);
    List<SensorDto> sensors = registry.listSensors(deviceId).stream().map(SensorDto::from).toList();
    return ResponseEntity.ok(ListResponse.of(sensors));
  }

  @PostMapping("/{deviceId}:activate")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> activate(@PathVariable String deviceId,
                                       @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /devices/{}:activate caller={}", deviceId, caller.getSubject());
    registry.activate(deviceId, caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{deviceId}:suspend")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> suspend(@PathVariable String deviceId,
                                      @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /devices/{}:suspend caller={}", deviceId, caller.getSubject());
    registry.suspend(deviceId, caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{deviceId}:decommission")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> decommission(@PathVariable String deviceId,
                                           @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /devices/{}:decommission caller={}", deviceId, caller.getSubject());
    registry.decommission(deviceId, caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.noContent().build();
  }

  private static RegistryService.RegisterDeviceCommand toCommand(RegisterDeviceRequest req) {
    return new RegistryService.RegisterDeviceCommand(req.deviceId(), req.category(), req.deviceType(),
        req.zone(), req.parentGatewayId(), req.firmwareVersion(), req.protocolsOrEmpty());
  }
}
