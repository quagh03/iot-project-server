package com.huylq.iotprojectserver.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window counter, one window per UTC minute. Good enough for single-instance
 * deployment; profile-gated Redis variant takes over when scaling horizontally.
 */
@Component
@ConditionalOnProperty(name = "iot.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryRateLimiter implements RateLimiter {

  private final ConcurrentHashMap<String, Window> buckets = new ConcurrentHashMap<>();

  @Override
  public Decision tryAcquire(String key, int limit) {
    long minute = Instant.now().getEpochSecond() / 60;
    Window w = buckets.compute(key, (k, prev) -> {
      if (prev == null || prev.minute != minute) return new Window(minute);
      return prev;
    });
    int current = w.count.incrementAndGet();
    long resetIn = 60 - (Instant.now().getEpochSecond() % 60);
    if (current > limit) {
      return new Decision(false, limit, 0, resetIn);
    }
    return new Decision(true, limit, Math.max(0, limit - current), resetIn);
  }

  private static final class Window {
    final long minute;
    final AtomicInteger count = new AtomicInteger();

    Window(long minute) {
      this.minute = minute;
    }
  }
}
