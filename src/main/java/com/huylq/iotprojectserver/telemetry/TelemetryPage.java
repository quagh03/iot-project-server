package com.huylq.iotprojectserver.telemetry;

import java.util.List;

/**
 * One keyset page of history rows plus the opaque cursor for the next page.
 */
public record TelemetryPage(List<Telemetry> items, String nextCursor, boolean hasMore) {
}
