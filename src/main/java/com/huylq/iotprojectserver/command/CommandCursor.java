package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque keyset cursor for {@code GET /v1/commands} — encodes the last row's
 * {@code (issuedAt, commandId)}. Unlike {@code telemetry.TelemetryCursor}, {@code Command}
 * has no numeric surrogate id (its PK is the app-assigned {@code commandId} string), so
 * {@code commandId} itself is the tiebreaker.
 */
public record CommandCursor(OffsetDateTime issuedAt, String commandId) {

  private static final String SEPARATOR = "|";

  public String encode() {
    String raw = issuedAt + SEPARATOR + commandId;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static CommandCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(SEPARATOR);
      OffsetDateTime issuedAt = OffsetDateTime.parse(raw.substring(0, sep));
      String commandId = raw.substring(sep + 1);
      return new CommandCursor(issuedAt, commandId);
    } catch (RuntimeException e) {
      throw ApiException.unprocessable("Invalid cursor");
    }
  }
}
