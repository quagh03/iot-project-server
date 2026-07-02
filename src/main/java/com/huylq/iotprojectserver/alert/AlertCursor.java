package com.huylq.iotprojectserver.alert;

import com.huylq.iotprojectserver.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque keyset cursor for {@code GET /v1/alerts} — encodes the last row's {@code
 * (createdAt, id)}; {@code id} is the tiebreaker since {@code createdAt} alone isn't
 * guaranteed unique. Mirrors {@code telemetry.TelemetryCursor}.
 */
public record AlertCursor(OffsetDateTime createdAt, Long id) {

  private static final String SEPARATOR = "|";

  public String encode() {
    String raw = createdAt + SEPARATOR + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static AlertCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(SEPARATOR);
      OffsetDateTime createdAt = OffsetDateTime.parse(raw.substring(0, sep));
      Long id = Long.parseLong(raw.substring(sep + 1));
      return new AlertCursor(createdAt, id);
    } catch (RuntimeException e) {
      throw ApiException.unprocessable("Invalid cursor");
    }
  }
}
