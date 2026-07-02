package com.huylq.iotprojectserver.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base type for domain errors that carry a stable {@link ErrorType} and HTTP status.
 * Throw in service code; {@code ApiExceptionHandler} renders the RFC 9457 response.
 */
@Getter
public class ApiException extends RuntimeException {

  private final ErrorType errorType;
  private final HttpStatus status;

  public ApiException(ErrorType errorType, HttpStatus status, String message) {
    super(message);
    this.errorType = errorType;
    this.status = status;
  }

  public ApiException(ErrorType errorType, HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.errorType = errorType;
    this.status = status;
  }

  public static ApiException notFound(String detail) {
    return new ApiException(ErrorType.NOT_FOUND, HttpStatus.NOT_FOUND, detail);
  }

  public static ApiException conflict(String detail) {
    return new ApiException(ErrorType.CONFLICT, HttpStatus.CONFLICT, detail);
  }

  public static ApiException invalidLifecycleTransition(String detail) {
    return new ApiException(ErrorType.INVALID_LIFECYCLE_TRANSITION, HttpStatus.CONFLICT, detail);
  }

  public static ApiException forbidden(String detail) {
    return new ApiException(ErrorType.FORBIDDEN, HttpStatus.FORBIDDEN, detail);
  }

  public static ApiException unprocessable(String detail) {
    return new ApiException(ErrorType.VALIDATION, HttpStatus.UNPROCESSABLE_ENTITY, detail);
  }

  public static ApiException tokenRevoked(String detail) {
    return new ApiException(ErrorType.TOKEN_REVOKED, HttpStatus.UNAUTHORIZED, detail);
  }

  public static ApiException safetyInterlock(String detail) {
    return new ApiException(ErrorType.SAFETY_INTERLOCK, HttpStatus.CONFLICT, detail);
  }
}
