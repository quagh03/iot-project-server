package com.huylq.iotprojectserver.api.dto;

import java.util.List;

/**
 * Bare {@code { "data": [...] }} envelope for non-paginated collection responses
 * (e.g. a gateway's sensor list). Paginated collections use
 * {@link com.huylq.iotprojectserver.common.pagination.PagedResponse} instead.
 */
public record ListResponse<T>(List<T> data) {

  public static <T> ListResponse<T> of(List<T> data) {
    return new ListResponse<>(data);
  }
}
