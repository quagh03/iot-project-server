package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryCursorTest {

  @Test
  void round_trips_through_encode_decode() {
    TelemetryCursor original = new TelemetryCursor(
        OffsetDateTime.of(2026, 6, 25, 10, 30, 0, 0, ZoneOffset.UTC), 42L);

    TelemetryCursor decoded = TelemetryCursor.decode(original.encode());

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void malformed_cursor_is_rejected_as_unprocessable() {
    assertThatThrownBy(() -> TelemetryCursor.decode("not-a-valid-cursor!!"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void empty_cursor_is_rejected_as_unprocessable() {
    assertThatThrownBy(() -> TelemetryCursor.decode(""))
        .isInstanceOf(ApiException.class);
  }
}
