package com.huylq.iotprojectserver.api.dto.device;

import com.huylq.iotprojectserver.registry.Device;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Registration payload (OpenAPI {@code RegisterDeviceRequest}).
 *
 * <p>Shape-level validation lives here ({@code @NotBlank} etc.); the
 * {@code parentGatewayId}-vs-{@code category} consistency rule that yields a
 * {@code 422} is a domain rule enforced in {@code RegistryService}.
 */
public record RegisterDeviceRequest(
    @NotBlank @Size(max = 64) String deviceId,
    @NotNull Device.Category category,
    @NotBlank @Size(max = 32) String deviceType,
    @NotBlank @Size(max = 64) String zone,
    @Size(max = 64) String parentGatewayId,
    @Size(max = 32) String firmwareVersion,
    List<String> protocols) {

  public List<String> protocolsOrEmpty() {
    return protocols == null ? List.of() : protocols;
  }
}
