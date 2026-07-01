package com.huylq.iotprojectserver.api.dto.device;

import jakarta.validation.constraints.Size;

/**
 * Partial update of mutable device metadata (OpenAPI {@code UpdateDeviceRequest}).
 *
 * <p>Status is deliberately absent — lifecycle is driven by the explicit
 * {@code :activate} / {@code :suspend} / {@code :decommission} actions. All
 * fields optional, but at least one must be present (enforced in the service).
 */
public record UpdateDeviceRequest(
    @Size(max = 64) String zone,
    @Size(max = 32) String deviceType,
    @Size(max = 32) String firmwareVersion) {

  public boolean isEmpty() {
    return zone == null && deviceType == null && firmwareVersion == null;
  }
}
