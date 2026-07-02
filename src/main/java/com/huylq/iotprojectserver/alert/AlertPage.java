package com.huylq.iotprojectserver.alert;

import java.util.List;

/**
 * One keyset page of alert history plus the opaque cursor for the next page.
 */
public record AlertPage(List<Alert> items, String nextCursor, boolean hasMore) {
}
