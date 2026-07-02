package com.huylq.iotprojectserver.api.dto.rule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/replace payload (OpenAPI {@code RuleInput}, API §9). {@code condition}/{@code
 * action} are shape-required here; the restricted-grammar validation that actually
 * decides whether they're safe to store lives in {@code RuleService}.
 */
public record RuleInputRequest(
    @NotBlank @Size(max = 128) String name,
    Boolean enabled,
    @NotBlank String condition,
    @NotBlank String action,
    Integer priority) {
}
