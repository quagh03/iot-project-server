package com.huylq.iotprojectserver.api.dto.command;

import com.huylq.iotprojectserver.command.Command;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Full command record (OpenAPI {@code Command}) — poll {@code GET /commands/{commandId}}
 * for lifecycle changes.
 */
public record CommandDto(
    String commandId,
    String targetId,
    String type,
    String action,
    Map<String, Object> parameters,
    Command.Status status,
    String issuedBy,
    OffsetDateTime issuedAt,
    OffsetDateTime receivedAt,
    OffsetDateTime executedAt) {

  public static CommandDto from(Command c) {
    return new CommandDto(c.getCommandId(), c.getTarget().getDeviceId(), c.getType(), c.getAction(),
        c.getParameters(), c.getStatus(), c.getIssuedBy(), c.getIssuedAt(), c.getReceivedAt(), c.getExecutedAt());
  }
}
