package com.huylq.iotprojectserver.api.dto.device;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Full scope set for a device credential (OpenAPI {@code ScopeSet}).
 *
 * <p>Each entry must be one of {@code telemetry:publish}, {@code command:subscribe},
 * {@code command:ack}, {@code heartbeat:publish}. The colon-bearing values can't be
 * Java enum constants, so membership is validated in the service (unknown → {@code 422}).
 */
public record ScopeSetDto(@NotNull List<String> scopes) {
}
