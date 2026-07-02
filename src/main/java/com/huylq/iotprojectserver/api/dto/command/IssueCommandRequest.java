package com.huylq.iotprojectserver.api.dto.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Issue payload (OpenAPI {@code IssueCommandRequest}, API §8). {@code type} is required
 * by the contract but not trusted operationally — the server derives the actuator's real
 * {@code deviceType} from the registry rather than the caller-supplied value. Parameter
 * whitelisting and the non-{@code ACTIVE}-actuator check are domain rules enforced in
 * {@code CommandService}.
 */
public record IssueCommandRequest(
    @NotBlank @Size(max = 64) String targetId,
    @NotBlank @Size(max = 32) String type,
    @NotBlank @Size(max = 32) String action,
    Map<String, Object> parameters,
    Boolean override,
    @Size(max = 500) String overrideReason) {

  public Map<String, Object> parametersOrEmpty() {
    return parameters == null ? Map.of() : parameters;
  }

  public boolean overrideOrFalse() {
    return Boolean.TRUE.equals(override);
  }
}
