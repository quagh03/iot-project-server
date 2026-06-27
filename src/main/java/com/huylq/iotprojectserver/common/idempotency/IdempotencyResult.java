package com.huylq.iotprojectserver.common.idempotency;

import java.util.Map;

/**
 * Outcome of an idempotency lookup.
 *
 * <ul>
 *   <li>{@link #fresh()} — no prior key; caller proceeds and later calls {@code store}.</li>
 *   <li>{@link #replay(Short, Map)} — prior result exists for an identical request; replay it.</li>
 *   <li>{@link #conflict()} — same key but different request body; caller must return 409.</li>
 * </ul>
 */
public record IdempotencyResult(Kind kind, Short responseStatus, Map<String, Object> responseBody) {

  public enum Kind {FRESH, REPLAY, CONFLICT}

  public static IdempotencyResult fresh() {
    return new IdempotencyResult(Kind.FRESH, null, null);
  }

  public static IdempotencyResult replay(Short status, Map<String, Object> body) {
    return new IdempotencyResult(Kind.REPLAY, status, body);
  }

  public static IdempotencyResult conflict() {
    return new IdempotencyResult(Kind.CONFLICT, null, null);
  }
}
