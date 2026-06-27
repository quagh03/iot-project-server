package com.huylq.iotprojectserver.common.correlation;

import org.slf4j.MDC;

/**
 * Correlation id plumbing — one trace id per inbound request, carried across every log line on
 * the request thread via the SLF4J {@link MDC}.
 *
 * <p>{@link CorrelationIdFilter} binds the id at the edge; the logging pattern renders it from
 * {@code %X{correlationId}}, so no controller or service needs to pass it explicitly.
 */
public final class CorrelationId {

  /** Request/response header carrying the trace id across service hops. */
  public static final String HEADER = "X-Correlation-Id";

  /** SLF4J MDC key — referenced by the logging pattern as {@code %X{correlationId}}. */
  public static final String MDC_KEY = "correlationId";

  private CorrelationId() {
  }

  /** The correlation id bound to the current request thread, or {@code null} outside a request. */
  public static String current() {
    return MDC.get(MDC_KEY);
  }
}
