package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.error.ErrorType;
import com.huylq.iotprojectserver.security.detection.SecurityDetectionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * RFC 9457 Problem Details renderer for every error the API produces.
 *
 * <p>One shape everywhere; stack traces never leak; validation lists every failing field.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private final SecurityDetectionService securityDetection;

  // ---- Domain exceptions ------------------------------------------------------------------

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest req) {
    return entity(problem(ex.getStatus(), ex.getErrorType(), ex.getMessage(), req));
  }

  // ---- Security ---------------------------------------------------------------------------

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
    securityDetection.recordAccessDenied(AuthController.clientIp(req), req.getRequestURI());
    return entity(problem(HttpStatus.FORBIDDEN, ErrorType.FORBIDDEN,
        "Insufficient role or scope for this resource", req));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
    return entity(problem(HttpStatus.UNAUTHORIZED, ErrorType.UNAUTHENTICATED,
        "Authentication required", req));
  }

  // ---- Validation -------------------------------------------------------------------------

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
                                                                 HttpServletRequest req) {
    List<Map<String, String>> errors = ex.getConstraintViolations().stream()
        .map(GlobalExceptionHandler::violationToField)
        .toList();
    ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, ErrorType.VALIDATION,
        "Validation failed", req);
    pd.setProperty("errors", errors);
    return entity(pd);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
                                                           HttpServletRequest req) {
    // DB-level uniqueness / FK breach surfaces as 409 to the caller.
    log.warn("Data integrity violation on {}", req.getRequestURI(), ex);
    return entity(problem(HttpStatus.CONFLICT, ErrorType.CONFLICT,
        "Resource state conflict", req));
  }

  // ---- Spring MVC overrides (return ProblemDetail with our type URIs) ---------------------

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                HttpHeaders headers,
                                                                HttpStatusCode status,
                                                                WebRequest request) {
    List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
        .map(f -> Map.of(
            "field", f.getField(),
            "message", f.getDefaultMessage() == null ? "invalid" : f.getDefaultMessage()))
        .toList();
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    pd.setType(ErrorType.VALIDATION.uri());
    pd.setTitle("Validation failed");
    pd.setDetail("One or more fields failed validation");
    pd.setInstance(instanceFrom(request));
    pd.setProperty("errors", errors);
    return ResponseEntity.unprocessableEntity().body(pd);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                HttpHeaders headers,
                                                                HttpStatusCode status,
                                                                WebRequest request) {
    return ResponseEntity.badRequest().body(
        problemForRequest(HttpStatus.BAD_REQUEST, ErrorType.MALFORMED,
            "Request body is malformed", request));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                   HttpHeaders headers,
                                                                   HttpStatusCode status,
                                                                   WebRequest request) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(
        problemForRequest(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorType.MALFORMED,
            "Unsupported media type", request));
  }

  @Override
  protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                        HttpHeaders headers,
                                                                        HttpStatusCode status,
                                                                        WebRequest request) {
    ProblemDetail pd = problemForRequest(HttpStatus.BAD_REQUEST, ErrorType.VALIDATION,
        "Missing required query parameter: " + ex.getParameterName(), request);
    pd.setProperty("errors", List.of(Map.of("field", ex.getParameterName(), "message", "required")));
    return ResponseEntity.badRequest().body(pd);
  }

  @Override
  protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
                                                                        HttpHeaders headers,
                                                                        HttpStatusCode status,
                                                                        WebRequest request) {
    String field = ex instanceof org.springframework.web.bind.MissingRequestHeaderException mrhe
        ? mrhe.getHeaderName() : "request";
    ProblemDetail pd = problemForRequest(HttpStatus.BAD_REQUEST, ErrorType.VALIDATION,
        "Missing or invalid required header: " + field, request);
    pd.setProperty("errors", List.of(Map.of("field", field, "message", "required")));
    return ResponseEntity.badRequest().body(pd);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                          HttpServletRequest req) {
    ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, ErrorType.VALIDATION,
        "Invalid value for parameter: " + ex.getName(), req);
    pd.setProperty("errors", List.of(Map.of("field", ex.getName(), "message", "type mismatch")));
    return entity(pd);
  }

  @Override
  protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
        problemForRequest(HttpStatus.NOT_FOUND, ErrorType.NOT_FOUND,
            "Resource not found", request));
  }

  // ---- Fallback ---------------------------------------------------------------------------

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
    log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
    return entity(problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorType.INTERNAL,
        "An unexpected error occurred", req));
  }

  // ---- helpers ----------------------------------------------------------------------------

  private static ProblemDetail problem(HttpStatus status, ErrorType type, String detail,
                                       HttpServletRequest req) {
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(type.uri());
    pd.setTitle(status.getReasonPhrase());
    pd.setDetail(detail);
    pd.setInstance(URI.create(req.getRequestURI()));
    return pd;
  }

  private static ProblemDetail problemForRequest(HttpStatus status, ErrorType type, String detail,
                                                 WebRequest request) {
    ProblemDetail pd = ProblemDetail.forStatus(status);
    pd.setType(type.uri());
    pd.setTitle(status.getReasonPhrase());
    pd.setDetail(detail);
    pd.setInstance(instanceFrom(request));
    return pd;
  }

  private static URI instanceFrom(WebRequest request) {
    String desc = request.getDescription(false); // "uri=/path"
    if (desc != null && desc.startsWith("uri=")) {
      return URI.create(desc.substring(4));
    }
    return URI.create("");
  }

  private static Map<String, String> violationToField(ConstraintViolation<?> v) {
    return Map.of(
        "field", v.getPropertyPath().toString(),
        "message", v.getMessage());
  }

  private static ResponseEntity<ProblemDetail> entity(ProblemDetail pd) {
    return ResponseEntity.status(pd.getStatus()).body(pd);
  }
}
