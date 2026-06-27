package com.huylq.iotprojectserver.common.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Binds a correlation id to every request so all log lines on the request thread trace back to one
 * client call. Reads the inbound {@value CorrelationId#HEADER} header (e.g. propagated by an
 * upstream gateway) and generates a fresh UUID when it is absent, blank, or unsafe. The id is put
 * into the SLF4J MDC under {@value CorrelationId#MDC_KEY} — the logging pattern stamps it onto
 * every line, so nothing downstream has to pass it explicitly — and echoed back on the response so
 * callers can report it when raising issues.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so the id is in place before the security
 * chain, the rate-limit filter, controllers, and services emit any logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  private static final int MAX_LENGTH = 64;

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String correlationId = req.getHeader(CorrelationId.HEADER);
    if (!isSafe(correlationId)) {
      correlationId = UUID.randomUUID().toString();
    }
    MDC.put(CorrelationId.MDC_KEY, correlationId);
    res.setHeader(CorrelationId.HEADER, correlationId);
    try {
      chain.doFilter(req, res);
    } finally {
      // Never leak the id onto the next request handled by this pooled thread.
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  /**
   * Accepts a client-supplied id only if it is short and printable ASCII — rejecting control
   * characters (CR/LF) that could otherwise be used to forge log lines.
   */
  private static boolean isSafe(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c > 0x7e) {
        return false;
      }
    }
    return true;
  }
}
