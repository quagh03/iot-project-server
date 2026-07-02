package com.huylq.iotprojectserver.health;

import com.huylq.iotprojectserver.common.time.Clocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Optional defense-in-depth alongside LWT (implementation plan Phase 5) — a device whose
 * will never fired (e.g. the broker dropped without delivering it) still gets marked
 * {@code OFFLINE} once its {@code last_seen} goes stale, rather than staying falsely
 * {@code ONLINE} forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthStalenessSweeper {

  private final DeviceHealthRepository repo;
  private final HealthSweepProperties props;

  @Scheduled(fixedDelayString = "PT1M")
  @Transactional
  public void sweep() {
    OffsetDateTime cutoff = Clocks.nowUtc().minus(props.staleAfter());
    int marked = repo.markStaleOffline(cutoff);
    if (marked > 0) {
      log.info("Staleness sweep marked {} device(s) OFFLINE (last_seen before {})", marked, cutoff);
    }
  }
}
