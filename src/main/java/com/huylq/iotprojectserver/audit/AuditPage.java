package com.huylq.iotprojectserver.audit;

import java.util.List;

/**
 * One keyset page of audit entries plus the opaque cursor for the next page.
 */
public record AuditPage(List<AuditLog> items, String nextCursor, boolean hasMore) {
}
