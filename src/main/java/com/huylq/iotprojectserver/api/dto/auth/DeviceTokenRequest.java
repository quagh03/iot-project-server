package com.huylq.iotprojectserver.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * OAuth2 client-credentials token request, bound from an
 * {@code application/x-www-form-urlencoded} body.
 *
 * <p>Bound via Spring constructor data-binding, which matches on the component name —
 * so the components keep the OAuth2 wire names ({@code grant_type}, {@code client_id},
 * {@code client_secret}) rather than camelCase. Validation runs from the controller's
 * {@code @Valid}; failures surface as {@code MethodArgumentNotValidException} and are
 * rendered by the global handler.
 */
public record DeviceTokenRequest(
    @NotBlank
    @Pattern(regexp = "client_credentials", message = "unsupported grant_type")
    String grant_type,

    @NotBlank String client_id,

    @NotBlank String client_secret,

    String scope) {

  /**
   * Space-delimited {@code scope} parsed into a set; empty when absent or blank.
   */
  public Set<String> requestedScopes() {
    if (scope == null || scope.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(scope.trim().split("\\s+"))
        .collect(Collectors.toUnmodifiableSet());
  }
}
