package com.huylq.iotprojectserver.api.dto.telemetry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * HTTP fallback ingest payload (OpenAPI {@code TelemetryBatch}, API §5). Shape-level
 * validation lives here; the registry-derived sensorType whitelist and the payload
 * identity check are domain rules enforced in {@code TelemetryService}.
 */
public record TelemetryIngestRequest(
    @NotBlank @Size(max = 64) String gatewayId,
    @NotBlank @Size(max = 64) String zone,
    @NotEmpty @Valid List<ReadingRequest> readings) {

  public record ReadingRequest(
      @NotBlank @Size(max = 64) String sensorId,
      @NotBlank @Size(max = 32) String sensorType,
      Double valueNum,
      Boolean valueBool,
      @Size(max = 16) String unit,
      @NotNull OffsetDateTime ts) {

    @AssertTrue(message = "Exactly one of valueNum/valueBool is required")
    public boolean isValueShapeValid() {
      return (valueNum != null) != (valueBool != null);
    }
  }
}
