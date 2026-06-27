package com.huylq.iotprojectserver.common.time;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * UTC time helpers. All timestamps on the wire are ISO-8601 UTC per API §1 —
 * use these helpers rather than {@code OffsetDateTime.now()} so tests can swap clocks.
 */
public final class Clocks {

  private static volatile Clock clock = Clock.systemUTC();

  private Clocks() {
  }

  public static OffsetDateTime nowUtc() {
    return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
  }

  /**
   * Test seam — production code should never call this.
   */
  public static void setClock(Clock c) {
    clock = c;
  }
}
