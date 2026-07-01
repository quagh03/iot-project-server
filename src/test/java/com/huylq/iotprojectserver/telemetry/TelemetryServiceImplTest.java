package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.RegistryService;
import com.huylq.iotprojectserver.registry.Sensor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceImplTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private TelemetryRepository telemetryRepo;
  @Mock private SensorLatestRepository sensorLatestRepo;
  @Mock private RegistryService registry;
  @Mock private RuleEventPublisher ruleEvents;

  private TelemetryServiceImpl service;

  @BeforeEach
  void setUp() {
    TelemetryIngestProperties props = new TelemetryIngestProperties(
        Duration.ofMinutes(5), Duration.ofHours(1), Duration.ofDays(7));
    service = new TelemetryServiceImpl(telemetryRepo, sensorLatestRepo, registry, ruleEvents, props);
    Clocks.setClock(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    Clocks.setClock(Clock.systemUTC());
  }

  private Sensor sensorOf(String sensorId, String type, String gatewayId) {
    Device gateway = Device.builder().deviceId(gatewayId).build();
    return Sensor.builder().sensorId(sensorId).type(type).gateway(gateway).build();
  }

  @Test
  void identity_mismatch_is_forbidden() {
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", 22.4, null, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_2", NOW);

    assertThatThrownBy(() -> service.ingest(cmd))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

    verify(telemetryRepo, never()).saveAll(anyList());
  }

  @Test
  void unknown_sensor_id_is_unprocessable() {
    when(registry.findSensor("s_missing")).thenReturn(Optional.empty());
    ReadingCommand reading = new ReadingCommand("s_missing", "temp", 22.4, null, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    assertThatThrownBy(() -> service.ingest(cmd))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void sensor_type_mismatch_is_unprocessable() {
    when(registry.findSensor("s_temp_1")).thenReturn(Optional.of(sensorOf("s_temp_1", "temp", "gw_1")));
    ReadingCommand reading = new ReadingCommand("s_temp_1", "smoke", null, true, null, NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    assertThatThrownBy(() -> service.ingest(cmd))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void sensor_registered_under_a_different_gateway_is_unprocessable() {
    when(registry.findSensor("s_temp_1")).thenReturn(Optional.of(sensorOf("s_temp_1", "temp", "gw_other")));
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", 22.4, null, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    assertThatThrownBy(() -> service.ingest(cmd))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void both_valueNum_and_valueBool_present_is_unprocessable() {
    // XOR is checked before the registry lookup, so no sensor stub is needed here.
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", 22.4, true, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    assertThatThrownBy(() -> service.ingest(cmd))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void neither_valueNum_nor_valueBool_present_is_unprocessable() {
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", null, null, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    assertThatThrownBy(() -> service.ingest(cmd))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void implausibly_stale_reading_is_flagged_but_still_ingested() {
    when(registry.findSensor("s_temp_1")).thenReturn(Optional.of(sensorOf("s_temp_1", "temp", "gw_1")));
    OffsetDateTime staleTs = NOW.minusHours(5); // beyond the 1h max-past-skew configured in setUp
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", 22.4, null, "C", staleTs);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    service.ingest(cmd);

    verify(telemetryRepo, times(1)).saveAll(anyList());
    verify(sensorLatestRepo, times(1)).upsert("s_temp_1", "office_1", "temp", 22.4, null, "C", staleTs);
    verify(ruleEvents, times(1)).publish(any());
  }

  @Test
  void happy_path_persists_and_upserts_and_publishes_seam_event() {
    when(registry.findSensor("s_temp_1")).thenReturn(Optional.of(sensorOf("s_temp_1", "temp", "gw_1")));
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", 22.4, null, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), "gw_1", NOW);

    service.ingest(cmd);

    verify(telemetryRepo).saveAll(anyList());
    verify(sensorLatestRepo).upsert("s_temp_1", "office_1", "temp", 22.4, null, "C", NOW);
    verify(ruleEvents).publish(any());
  }

  @Test
  void mqtt_path_has_no_authenticated_device_id_and_skips_identity_check() {
    when(registry.findSensor("s_temp_1")).thenReturn(Optional.of(sensorOf("s_temp_1", "temp", "gw_1")));
    ReadingCommand reading = new ReadingCommand("s_temp_1", "temp", 22.4, null, "C", NOW);
    TelemetryIngestCommand cmd = new TelemetryIngestCommand("office_1", "gw_1", List.of(reading), null, NOW);

    service.ingest(cmd);

    verify(telemetryRepo).saveAll(anyList());
  }

  @Test
  void latest_throws_not_found_when_no_current_reading_exists() {
    when(sensorLatestRepo.findById("s_missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.latest("s_missing"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void query_history_reports_hasMore_when_extra_row_fetched() {
    Telemetry row1 = Telemetry.builder().id(3L).ts(NOW).sensorId("s_temp_1").sensorType("temp")
        .valueNum(1.0).zone("office_1").gatewayId("gw_1").build();
    Telemetry row2 = Telemetry.builder().id(2L).ts(NOW.minusMinutes(1)).sensorId("s_temp_1").sensorType("temp")
        .valueNum(2.0).zone("office_1").gatewayId("gw_1").build();
    Telemetry extra = Telemetry.builder().id(1L).ts(NOW.minusMinutes(2)).sensorId("s_temp_1").sensorType("temp")
        .valueNum(3.0).zone("office_1").gatewayId("gw_1").build();
    when(telemetryRepo.findBySensorPage(any(), any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(List.of(row1, row2, extra));

    TelemetryPage page = service.queryHistory("s_temp_1", null, NOW.minusHours(1), NOW, null, 2);

    assertThat(page.items()).containsExactly(row1, row2);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.nextCursor()).isNotNull();
  }
}
