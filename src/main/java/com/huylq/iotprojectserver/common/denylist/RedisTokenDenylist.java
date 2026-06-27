package com.huylq.iotprojectserver.common.denylist;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed denylist for multi-instance deploys.
 *
 * <p>Each entry's TTL is set on Redis side via {@code SET … EX}, so revocations
 * auto-expire when the underlying token would have anyway — no separate sweeper.
 *
 * <p>Activated by {@code iot.redis.enabled=true} (see {@code application-prod.yaml}).
 */
@Component
@ConditionalOnProperty(name = "iot.redis.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RedisTokenDenylist implements TokenDenylist {

  private static final String JTI_PREFIX = "denylist:jti:";
  private static final String REFRESH_PREFIX = "denylist:refresh:";
  private static final String DENIED = "1";

  private final StringRedisTemplate redis;

  @Override
  public void blacklistAccessJti(String jti, Duration ttl) {
    redis.opsForValue().set(JTI_PREFIX + jti, DENIED, ttl);
  }

  @Override
  public void blacklistRefreshHash(String hash, Duration ttl) {
    redis.opsForValue().set(REFRESH_PREFIX + hash, DENIED, ttl);
  }

  @Override
  public boolean isAccessBlacklisted(String jti) {
    return jti != null && Boolean.TRUE.equals(redis.hasKey(JTI_PREFIX + jti));
  }

  @Override
  public boolean isRefreshBlacklisted(String hash) {
    return hash != null && Boolean.TRUE.equals(redis.hasKey(REFRESH_PREFIX + hash));
  }
}
