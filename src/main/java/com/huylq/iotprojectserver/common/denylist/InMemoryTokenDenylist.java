package com.huylq.iotprojectserver.common.denylist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation, used when Redis is disabled (dev, tests, single-instance).
 * Entries hold their absolute expiry; a periodic sweep prunes stale keys.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "iot.redis.enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryTokenDenylist implements TokenDenylist {

  private final ConcurrentHashMap<String, Instant> jtis = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Instant> refreshHashes = new ConcurrentHashMap<>();

  @Override
  public void blacklistAccessJti(String jti, Duration ttl) {
    jtis.put(jti, Instant.now().plus(ttl));
  }

  @Override
  public void blacklistRefreshHash(String hash, Duration ttl) {
    refreshHashes.put(hash, Instant.now().plus(ttl));
  }

  @Override
  public boolean isAccessBlacklisted(String jti) {
    return jti != null && stillValid(jtis.get(jti));
  }

  @Override
  public boolean isRefreshBlacklisted(String hash) {
    return hash != null && stillValid(refreshHashes.get(hash));
  }

  private static boolean stillValid(Instant expiry) {
    if (expiry == null) return false;
    return expiry.isAfter(Instant.now());
  }

  @Scheduled(fixedDelayString = "PT5M")
  void prune() {
    int removed = sweep(jtis) + sweep(refreshHashes);
    if (removed > 0) log.debug("Pruned {} expired denylist entries", removed);
  }

  private static int sweep(ConcurrentHashMap<String, Instant> map) {
    Instant now = Instant.now();
    int before = map.size();
    map.entrySet().removeIf(e -> e.getValue().isBefore(now));
    return before - map.size();
  }
}
