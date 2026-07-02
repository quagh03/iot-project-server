package com.huylq.iotprojectserver.audit;

import com.huylq.iotprojectserver.common.error.ApiException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

/**
 * Opaque keyset cursor for {@code GET /v1/audit-logs} — encodes the last row's {@code
 * (ts, id)}; {@code id} is the tiebreaker since {@code ts} alone isn't unique. Mirrors
 * {@code telemetry.TelemetryCursor}. Valid across partitions: {@code id} is
 * {@code GENERATED ALWAYS AS IDENTITY} on the partitioned parent, so Postgres shares one
 * sequence across every monthly partition.
 */
public record AuditCursor(OffsetDateTime ts, Long id) {

  private static final String SEPARATOR = "|";

  public String encode() {
    String raw = ts + SEPARATOR + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static AuditCursor decode(String cursor) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(SEPARATOR);
      OffsetDateTime ts = OffsetDateTime.parse(raw.substring(0, sep));
      Long id = Long.parseLong(raw.substring(sep + 1));
      return new AuditCursor(ts, id);
    } catch (RuntimeException e) {
      throw ApiException.unprocessable("Invalid cursor");
    }
  }
}
