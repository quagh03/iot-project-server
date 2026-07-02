package com.huylq.iotprojectserver.command;

import java.util.List;

/**
 * One keyset page of command history plus the opaque cursor for the next page.
 */
public record CommandPage(List<Command> items, String nextCursor, boolean hasMore) {
}
