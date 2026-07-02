package com.huylq.iotprojectserver.common.ratelimit;

import com.huylq.iotprojectserver.support.AbstractRedisIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Redis (Testcontainers) — proves the counter is atomic and shared, the property
 * that matters for horizontal scale-out (a mocked/in-memory test can't demonstrate this).
 */
class RedisRateLimiterIT extends AbstractRedisIT {

  @Autowired RateLimiter rateLimiter;

  @Test
  void allows_requests_under_the_limit_and_tracks_remaining() {
    String key = "test:" + UUID.randomUUID();

    RateLimiter.Decision first = rateLimiter.tryAcquire(key, 3);
    RateLimiter.Decision second = rateLimiter.tryAcquire(key, 3);
    RateLimiter.Decision third = rateLimiter.tryAcquire(key, 3);

    assertThat(first.allowed()).isTrue();
    assertThat(first.remaining()).isEqualTo(2);
    assertThat(second.remaining()).isEqualTo(1);
    assertThat(third.remaining()).isEqualTo(0);
  }

  @Test
  void denies_once_the_limit_is_exceeded_within_the_window() {
    String key = "test:" + UUID.randomUUID();
    rateLimiter.tryAcquire(key, 2);
    rateLimiter.tryAcquire(key, 2);

    RateLimiter.Decision third = rateLimiter.tryAcquire(key, 2);

    assertThat(third.allowed()).isFalse();
    assertThat(third.remaining()).isZero();
    assertThat(third.resetSeconds()).isBetween(0L, 60L);
  }

  @Test
  void different_keys_have_independent_counters() {
    String keyA = "test:" + UUID.randomUUID();
    String keyB = "test:" + UUID.randomUUID();
    rateLimiter.tryAcquire(keyA, 1);

    RateLimiter.Decision decisionB = rateLimiter.tryAcquire(keyB, 1);

    assertThat(decisionB.allowed()).isTrue();
  }

  @Test
  void the_bean_wired_is_the_redis_implementation_when_enabled() {
    assertThat(rateLimiter).isInstanceOf(RedisRateLimiter.class);
  }
}
