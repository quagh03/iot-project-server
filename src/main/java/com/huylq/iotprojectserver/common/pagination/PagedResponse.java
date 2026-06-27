package com.huylq.iotprojectserver.common.pagination;

import java.util.List;

/**
 * Envelope returned by every list endpoint. {@code page} is either a {@link CursorPage}
 * or an {@link OffsetPage} depending on the collection's pagination shape.
 */
public record PagedResponse<T>(List<T> data, Object page) {

  public static <T> PagedResponse<T> cursor(List<T> data, CursorPage page) {
    return new PagedResponse<>(data, page);
  }

  public static <T> PagedResponse<T> offset(List<T> data, OffsetPage page) {
    return new PagedResponse<>(data, page);
  }
}
