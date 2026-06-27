package com.huylq.iotprojectserver.common.pagination;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounded pagination defaults — every list endpoint clamps page size to {@link #maxPageSize}.
 */
@ConfigurationProperties("iot.pagination")
public record PaginationConfig(int defaultPageSize, int maxPageSize) {

  public PaginationConfig {
    if (defaultPageSize <= 0) defaultPageSize = 50;
    if (maxPageSize <= 0) maxPageSize = 200;
  }

  public int clamp(Integer requested) {
    if (requested == null || requested <= 0) return defaultPageSize;
    return Math.min(requested, maxPageSize);
  }
}
