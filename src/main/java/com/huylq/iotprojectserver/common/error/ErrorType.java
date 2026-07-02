package com.huylq.iotprojectserver.common.error;

import java.net.URI;

/**
 * Stable, machine-readable Problem Details {@code type} URIs.
 *
 * <p>Clients branch on {@link #uri()}; {@code detail} is human-facing and may change.
 */
public enum ErrorType {
  VALIDATION("https://api.iot.example.com/errors/validation"),
  MALFORMED("https://api.iot.example.com/errors/malformed"),
  UNAUTHENTICATED("https://api.iot.example.com/errors/unauthenticated"),
  TOKEN_REVOKED("https://api.iot.example.com/errors/token-revoked"),
  FORBIDDEN("https://api.iot.example.com/errors/forbidden"),
  NOT_FOUND("https://api.iot.example.com/errors/not-found"),
  CONFLICT("https://api.iot.example.com/errors/conflict"),
  INVALID_LIFECYCLE_TRANSITION("https://api.iot.example.com/errors/invalid-lifecycle-transition"),
  RATE_LIMITED("https://api.iot.example.com/errors/rate-limited"),
  UNAVAILABLE("https://api.iot.example.com/errors/unavailable"),
  SAFETY_INTERLOCK("https://api.iot.example.com/errors/safety-interlock"),
  INTERNAL("https://api.iot.example.com/errors/internal");

  private final URI uri;

  ErrorType(String uri) {
    this.uri = URI.create(uri);
  }

  public URI uri() {
    return uri;
  }
}
