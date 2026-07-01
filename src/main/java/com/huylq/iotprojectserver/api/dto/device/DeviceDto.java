package com.huylq.iotprojectserver.api.dto.device;

import com.huylq.iotprojectserver.registry.Device;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Device wire shape (OpenAPI {@code Device}). Exposes only registry metadata —
 * never credentials, secrets, or internal version counters.
 */
public record DeviceDto(
    String deviceId,
    Device.Category category,
    String deviceType,
    String zone,
    String parentGatewayId,
    String firmwareVersion,
    Device.Status status,
    List<String> protocols,
    OffsetDateTime createdAt) {

  public static DeviceDto from(Device d) {
    String parentId = d.getParentGateway() == null ? null : d.getParentGateway().getDeviceId();
    String[] protocols = d.getProtocols() == null ? new String[0] : d.getProtocols();
    return new DeviceDto(
        d.getDeviceId(),
        d.getCategory(),
        d.getDeviceType(),
        d.getZone(),
        parentId,
        d.getFirmwareVersion(),
        d.getStatus(),
        List.of(protocols),
        d.getCreatedAt());
  }
}
