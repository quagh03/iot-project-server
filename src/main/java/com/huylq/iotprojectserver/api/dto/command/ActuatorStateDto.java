package com.huylq.iotprojectserver.api.dto.command;

import com.huylq.iotprojectserver.command.ActuatorState;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * Desired-vs-reported mirror row (OpenAPI {@code ActuatorState}, API §6). {@code zone} is
 * resolved from the device registry (not stored on the mirror); {@code inFlight} is
 * server-computed ({@code desiredState != reportedState}) — a command in flight or a
 * drift to investigate.
 */
public record ActuatorStateDto(
    String deviceId,
    String zone,
    String desiredState,
    String reportedState,
    boolean inFlight,
    Map<String, Object> attributes,
    String lastCommandId,
    OffsetDateTime commandedAt,
    OffsetDateTime updatedAt) {

  public static ActuatorStateDto from(ActuatorState a) {
    return new ActuatorStateDto(a.getDeviceId(), a.getDevice().getZone(), a.getDesiredState(), a.getReportedState(),
        !Objects.equals(a.getDesiredState(), a.getReportedState()), a.getAttributes(), a.getLastCommandId(),
        a.getCommandedAt(), a.getUpdatedAt());
  }
}
