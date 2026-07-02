package com.huylq.iotprojectserver.health;

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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthStalenessSweeperTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private DeviceHealthRepository repo;

  private HealthStalenessSweeper sweeper;

  @BeforeEach
  void setUp() {
    sweeper = new HealthStalenessSweeper(repo, new HealthSweepProperties(Duration.ofMinutes(3)));
    Clocks.setClock(Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    Clocks.setClock(Clock.systemUTC());
  }

  @Test
  void sweep_marks_offline_any_device_whose_last_seen_is_older_than_the_configured_threshold() {
    when(repo.markStaleOffline(NOW.minusMinutes(3))).thenReturn(2);

    sweeper.sweep();

    verify(repo).markStaleOffline(NOW.minusMinutes(3));
  }
}
