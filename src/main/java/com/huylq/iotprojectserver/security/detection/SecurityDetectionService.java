package com.huylq.iotprojectserver.security.detection;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Detection & incident response (System Design §7): rate-triggered signals raise a
 * {@code CRITICAL}/{@code WARNING} {@link Alert} exactly once per fixed one-minute window
 * the first time a threshold is crossed — a fixed-window counter identical in shape to
 * {@code common.ratelimit.InMemoryRateLimiter}, just used for alerting instead of
 * denying. In-memory/single-instance for now (mirrors the rate limiter's own
 * in-memory/Redis split — not duplicated here since detection is best-effort, not a
 * security control that must be exact across instances).
 *
 * <p>Level-triggered signals (a safety sensor going quiet) don't fit this shape and are
 * handled separately — see {@code telemetry.SafetySensorGapDetector}.
 */
@Component
@Slf4j
public class SecurityDetectionService {

  private final AlertService alertService;
  private final DetectionProperties props;
  private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

  public SecurityDetectionService(AlertService alertService, DetectionProperties props) {
    this.alertService = alertService;
    this.props = props;
  }

  /** Repeated failed logins for the same username within a minute — credential stuffing/guessing. */
  public void recordAuthFailure(String username, String ip) {
    if (crossedThreshold("auth-failure:" + username, props.authFailureBurstThreshold())) {
      log.warn("Auth-failure burst detected for username='{}' from ip={}", username, ip);
      alertService.raise("AUTH_FAILURE_BURST", Alert.Severity.WARNING, null, null,
          "Repeated failed login attempts for user '" + username + "' from " + ip);
    }
  }

  /**
   * A single reuse is itself the signal (Phase 2.5 already cascade-revokes the chain) —
   * no burst window needed.
   */
  public void recordRefreshReuse(String userId) {
    alertService.raise("TOKEN_REUSE_DETECTED", Alert.Severity.CRITICAL, null, userId,
        "Refresh-token reuse detected for user " + userId
            + " — likely token theft; the reuse cascade has revoked every descendant token");
  }

  /** Sustained rate-limit denials for one identity — probing, or a compromised client hammering the API. */
  public void recordRateLimitDenial(String category, String key) {
    if (crossedThreshold("rate-limit:" + category + ":" + key, props.rateLimitSpikeThreshold())) {
      log.warn("Rate-limit spike detected: category={} key={}", category, key);
      alertService.raise("RATE_LIMIT_SPIKE", Alert.Severity.WARNING, null, null,
          "Sustained " + category + " rate-limit denials for " + key);
    }
  }

  /** Repeated 403s from one source — role/scope probing or enumeration. */
  public void recordAccessDenied(String ip, String path) {
    if (crossedThreshold("forbidden:" + ip, props.forbiddenSpikeThreshold())) {
      log.warn("Forbidden-response spike detected from ip={}", ip);
      alertService.raise("FORBIDDEN_SPIKE", Alert.Severity.WARNING, null, null,
          "Repeated 403 responses from " + ip + " (latest path " + path + ") — possible probing/enumeration");
    }
  }

  /** Repeated command timeouts for one device — command-suppression suspected (T3). */
  public void recordCommandTimeout(String targetDeviceId) {
    if (crossedThreshold("command-timeout:" + targetDeviceId, props.commandTimeoutBurstThreshold())) {
      log.warn("Command-timeout burst detected for device={}", targetDeviceId);
      alertService.raise("COMMAND_SUPPRESSION_SUSPECTED", Alert.Severity.CRITICAL, null, targetDeviceId,
          "Repeated command timeouts for device " + targetDeviceId + " — possible command suppression");
    }
  }

  /**
   * Fires exactly once per window on the call where the count first reaches {@code
   * threshold} — every call before and after still increments (so the window's true
   * count is available if ever needed), but only that one crossing call returns {@code
   * true}, keeping the alert volume proportional to distinct incidents, not distinct
   * requests.
   */
  private boolean crossedThreshold(String key, int threshold) {
    long minute = Instant.now().getEpochSecond() / 60;
    WindowCounter w = counters.compute(key, (k, prev) -> (prev == null || prev.minute != minute)
        ? new WindowCounter(minute) : prev);
    return w.count.incrementAndGet() == threshold;
  }

  private static final class WindowCounter {
    final long minute;
    final AtomicInteger count = new AtomicInteger();

    WindowCounter(long minute) {
      this.minute = minute;
    }
  }
}
