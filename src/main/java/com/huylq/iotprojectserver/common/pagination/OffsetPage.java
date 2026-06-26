package com.huylq.iotprojectserver.common.pagination;

/**
 * Pagination metadata for offset-based collections — devices, users, rules, sensors.
 *
 * @param offset zero-based starting index of this page
 * @param limit  page size (max 200)
 * @param total  total matching rows
 */
public record OffsetPage(int offset, int limit, long total) {
}
