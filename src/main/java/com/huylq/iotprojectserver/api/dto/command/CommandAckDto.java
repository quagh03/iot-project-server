package com.huylq.iotprojectserver.api.dto.command;

import com.huylq.iotprojectserver.command.Command;

import java.time.OffsetDateTime;

/**
 * Response on issue (OpenAPI {@code CommandAck}) — status is always {@code PENDING} here;
 * the actuator effect is asynchronous over MQTT.
 */
public record CommandAckDto(String commandId, Command.Status status, OffsetDateTime issuedAt) {

  public static CommandAckDto from(Command c) {
    return new CommandAckDto(c.getCommandId(), c.getStatus(), c.getIssuedAt());
  }
}
