package com.huylq.iotprojectserver.common.partition;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartitionManagerMetricsTest {

  private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");

  @Mock private JdbcTemplate jdbc;

  @Test
  void reports_partition_missing_when_the_current_months_partition_does_not_exist() {
    when(jdbc.queryForList(any(String.class), eq(String.class), eq("telemetry")))
        .thenReturn(List.of("telemetry_2000_01"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new PartitionManager(jdbc, new PartitionConfig(List.of("telemetry"), 0, true), registry);

    double missing = registry.get("iot.partition.missing").tag("table", "telemetry").gauge().value();
    assertThat(missing).isEqualTo(1.0);
  }

  @Test
  void reports_not_missing_when_the_current_months_partition_exists() {
    String currentPartition = "telemetry_" + YearMonth.now().format(SUFFIX);
    when(jdbc.queryForList(any(String.class), eq(String.class), eq("telemetry")))
        .thenReturn(List.of(currentPartition));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new PartitionManager(jdbc, new PartitionConfig(List.of("telemetry"), 0, true), registry);

    double missing = registry.get("iot.partition.missing").tag("table", "telemetry").gauge().value();
    assertThat(missing).isEqualTo(0.0);
  }

  @Test
  void reports_the_current_partition_byte_size() {
    when(jdbc.queryForObject(any(String.class), eq(Long.class), any(String.class))).thenReturn(4096L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new PartitionManager(jdbc, new PartitionConfig(List.of("telemetry"), 0, true), registry);

    double size = registry.get("iot.partition.size.bytes").tag("table", "telemetry").gauge().value();
    assertThat(size).isEqualTo(4096.0);
  }

  @Test
  void reports_zero_bytes_when_the_partition_does_not_exist() {
    when(jdbc.queryForObject(any(String.class), eq(Long.class), any(String.class))).thenReturn(null);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new PartitionManager(jdbc, new PartitionConfig(List.of("telemetry"), 0, true), registry);

    double size = registry.get("iot.partition.size.bytes").tag("table", "telemetry").gauge().value();
    assertThat(size).isEqualTo(0.0);
  }
}
