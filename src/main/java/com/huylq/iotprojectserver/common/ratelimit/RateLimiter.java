package com.huylq.iotprojectserver.common.ratelimit;

public interface RateLimiter {

  /**
   * Try to consume one unit. Returns a decision with remaining quota and reset time.
   */
  Decision tryAcquire(String key, int limitPerMinute);

  record Decision(boolean allowed, int limit, int remaining, long resetSeconds) {
  }
}
