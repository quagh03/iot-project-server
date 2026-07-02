package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertService;
import com.huylq.iotprojectserver.common.time.Clocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SafetySensorGapDetectorTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private SensorLatestRepository sensorLatestRepo;
  @Mock private AlertService alertService;

  private SafetySensorGapDetector detector;

  @BeforeEach
  void setUp() {
    detector = new SafetySensorGapDetector(sensorLatestRepo, alertService,
        new SafetySensorGapProperties(List.of("smoke"), Duration.ofMinutes(10)));
    Clocks.setClock(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    Clocks.setClock(Clock.systemUTC());
  }

  private static SensorLatest reading(String sensorId, OffsetDateTime ts) {
    return SensorLatest.builder().sensorId(sensorId).zone("office_1").sensorType("smoke")
        .valueBool(false).ts(ts).build();
  }

  @Test
  void raises_a_critical_alert_when_a_safety_sensor_goes_quiet() {
    when(sensorLatestRepo.findBySensorType("smoke"))
        .thenReturn(List.of(reading("s_smoke_1", NOW.minusMinutes(15))));

    detector.checkForGaps();

    verify(alertService).raise(eq("TELEMETRY_GAP"), eq(Alert.Severity.CRITICAL),
        eq("office_1"), eq("s_smoke_1"), any());
  }

  @Test
  void does_not_alert_for_a_sensor_reporting_within_the_window() {
    when(sensorLatestRepo.findBySensorType("smoke"))
        .thenReturn(List.of(reading("s_smoke_1", NOW.minusMinutes(2))));

    detector.checkForGaps();

    verify(alertService, never()).raise(any(), any(), any(), any(), any());
  }

  @Test
  void does_not_re_alert_on_every_sweep_while_the_gap_persists() {
    when(sensorLatestRepo.findBySensorType("smoke"))
        .thenReturn(List.of(reading("s_smoke_1", NOW.minusMinutes(15))));

    detector.checkForGaps();
    detector.checkForGaps();
    detector.checkForGaps();

    verify(alertService, times(1)).raise(any(), any(), any(), any(), any());
  }

  @Test
  void re_alerts_if_the_sensor_reports_then_gaps_again() {
    when(sensorLatestRepo.findBySensorType("smoke"))
        .thenReturn(List.of(reading("s_smoke_1", NOW.minusMinutes(15))));
    detector.checkForGaps();

    // Sensor reports again — clears the active gap.
    when(sensorLatestRepo.findBySensorType("smoke"))
        .thenReturn(List.of(reading("s_smoke_1", NOW.minusMinutes(1))));
    detector.checkForGaps();

    // Gaps again — should re-alert since the previous gap was cleared.
    when(sensorLatestRepo.findBySensorType("smoke"))
        .thenReturn(List.of(reading("s_smoke_1", NOW.minusMinutes(15))));
    detector.checkForGaps();

    verify(alertService, times(2)).raise(any(), any(), any(), any(), any());
  }
}
