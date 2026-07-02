package com.huylq.iotprojectserver.common.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Redis-backed fixed-window counter for multi-instance deploys (System Design §8 scaling
 * ladder step 5 — rate limits must be global, not per-instance, once horizontally scaled).
 * One key per {@code (category:identity, minute)}; {@code INCR} is atomic across every
 * app instance sharing the same Redis, so the count is exact regardless of which instance
 * a given request lands on. The key's TTL is set once on first use each window and
 * outlives the window by a safety margin so a slow request near the window boundary can't
 * read a just-expired counter as zero.
 *
 * <p>Activated by {@code iot.redis.enabled=true} — same flag and mirrors {@code
 * common.denylist.RedisTokenDenylist}'s pattern exactly.
 */
@Component
@ConditionalOnProperty(name = "iot.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

  private static final String PREFIX = "ratelimit:";
  private static final Duration KEY_TTL = Duration.ofSeconds(65);

  private final StringRedisTemplate redis;

  @Override
  public Decision tryAcquire(String key, int limitPerMinute) {
    long nowEpochSecond = Instant.now().getEpochSecond();
    long minute = nowEpochSecond / 60;
    String redisKey = PREFIX + key + ":" + minute;

    Long current = redis.opsForValue().increment(redisKey);
    if (current == null) {
      // Redis unreachable — fail open rather than take the whole API down over a
      // best-effort abuse control; the in-memory limiter would have started fresh too.
      current = 1L;
    } else if (current == 1L) {
      redis.expire(redisKey, KEY_TTL);
    }

    long resetSeconds = 60 - (nowEpochSecond % 60);
    if (current > limitPerMinute) {
      return new Decision(false, limitPerMinute, 0, resetSeconds);
    }
    return new Decision(true, limitPerMinute, (int) Math.max(0, limitPerMinute - current), resetSeconds);
  }
}
