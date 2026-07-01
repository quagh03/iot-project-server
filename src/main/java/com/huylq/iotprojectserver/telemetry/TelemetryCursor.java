package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque keyset cursor for {@code GET /v1/telemetry} — the first cursor-paged endpoint
 * in the codebase. Encodes the last row's {@code (ts, id)}; {@code id} is the tiebreaker
 * since {@code ts} alone isn't unique. Kept local to {@code telemetry} rather than a
 * shared {@code common.pagination} codec — no second consumer exists yet.
 */
public record TelemetryCursor(OffsetDateTime ts, Long id) {

  private static final String SEPARATOR = "|";

  public String encode() {
    String raw = ts + SEPARATOR + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static TelemetryCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(SEPARATOR);
      OffsetDateTime ts = OffsetDateTime.parse(raw.substring(0, sep));
      Long id = Long.parseLong(raw.substring(sep + 1));
      return new TelemetryCursor(ts, id);
    } catch (RuntimeException e) {
      throw ApiException.unprocessable("Invalid cursor");
    }
  }
}
