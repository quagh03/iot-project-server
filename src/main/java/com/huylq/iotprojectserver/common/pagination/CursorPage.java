package com.huylq.iotprojectserver.common.pagination;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Pagination metadata for cursor-based collections — telemetry, commands, alerts, audit-logs.
 *
 * @param nextCursor opaque cursor for the next page; null when {@code hasMore} is false
 * @param hasMore    true when more pages remain
 * @param pageSize   how many items in this page (max 200)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CursorPage(String nextCursor, boolean hasMore, int pageSize) {

  public static CursorPage of(String nextCursor, int pageSize) {
    return new CursorPage(nextCursor, nextCursor != null, pageSize);
  }

  public static CursorPage end(int pageSize) {
    return new CursorPage(null, false, pageSize);
  }
}
