package com.huylq.iotprojectserver.api.dto.alert;

import com.huylq.iotprojectserver.alert.Alert;

import java.time.OffsetDateTime;

/**
 * Alert record (OpenAPI {@code Alert}) — deliberately only the fields the contract
 * documents; {@code acknowledgedBy}/{@code acknowledgedAt}/{@code resolvedBy}/{@code
 * resolvedAt} are tracked on the entity for audit purposes but aren't part of this DTO.
 */
public record AlertDto(
    String alertId,
    String type,
    Alert.Severity severity,
    String zone,
    String sourceDeviceId,
    String message,
    Alert.Status status,
    OffsetDateTime createdAt) {

  public static AlertDto from(Alert a) {
    return new AlertDto(a.getId().toString(), a.getType(), a.getSeverity(), a.getZone(),
        a.getSourceDevice() == null ? null : a.getSourceDevice().getDeviceId(),
        a.getMessage(), a.getStatus(), a.getCreatedAt());
  }
}
