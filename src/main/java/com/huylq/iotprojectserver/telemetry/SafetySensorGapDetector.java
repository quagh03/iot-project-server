package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertService;
import com.huylq.iotprojectserver.common.time.Clocks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detection & incident response (System Design §7): "telemetry gap/anomaly on a safety
 * sensor" — a working smoke sensor that's gone quiet is exactly the tamper/T3 scenario a
 * safety system must never miss. Level-triggered, not rate-triggered, so it doesn't fit
 * {@code SecurityDetectionService}'s per-minute burst-counter shape: this fires exactly
 * once when a sensor crosses into "gapped" and auto-clears (allowing a future re-alert)
 * the moment it reports again, rather than re-alerting every sweep while the gap persists.
 *
 * <p>Only covers a safety sensor that has reported at least once before going quiet — a
 * sensor that has never reported at all would need a registry cross-reference (which
 * devices of this type exist) that's out of scope here; see the ops runbook.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SafetySensorGapDetector {

  private final SensorLatestRepository sensorLatestRepo;
  private final AlertService alertService;
  private final SafetySensorGapProperties props;
  private final Set<String> activeGaps = ConcurrentHashMap.newKeySet();

  @Scheduled(fixedDelayString = "PT1M")
  public void checkForGaps() {
    OffsetDateTime cutoff = Clocks.nowUtc().minus(props.maxAge());
    for (String sensorType : props.sensorTypes()) {
      for (SensorLatest reading : sensorLatestRepo.findBySensorType(sensorType)) {
        boolean gapped = reading.getTs().isBefore(cutoff);
        if (gapped) {
          if (activeGaps.add(reading.getSensorId())) {
            log.error("Safety-sensor gap detected: sensorId={} type={} zone={} lastSeen={}",
                reading.getSensorId(), sensorType, reading.getZone(), reading.getTs());
            alertService.raise("TELEMETRY_GAP", Alert.Severity.CRITICAL, reading.getZone(),
                reading.getSensorId(),
                "No " + sensorType + " reading from " + reading.getSensorId()
                    + " since " + reading.getTs() + " (expected within " + props.maxAge() + ")");
          }
        } else {
          activeGaps.remove(reading.getSensorId());
        }
      }
    }
  }
}
