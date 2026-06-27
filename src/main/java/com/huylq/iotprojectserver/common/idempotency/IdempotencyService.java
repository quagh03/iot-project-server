package com.huylq.iotprojectserver.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Replay store for {@code Idempotency-Key} headers — see API §1.
 *
 * <p>Usage pattern in a controller:
 * <pre>{@code
 *   var lookup = idempotency.lookup(key, "POST /v1/commands", requestBodyJson);
 *   switch (lookup.kind()) {
 *     case CONFLICT -> throw ApiException.conflict("Idempotency-Key reused with different body");
 *     case REPLAY   -> return rebuildResponseFrom(lookup);
 *     case FRESH    -> { ... do the work ... idempotency.store(key, endpoint, hash, status, body); }
 *   }
 * }</pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

  private final IdempotencyKeyRepository repo;
  private final IdempotencyConfig config;

  @Transactional(readOnly = true)
  public IdempotencyResult lookup(UUID key, String endpoint, String requestBody) {
    String hash = sha256Hex(requestBody);
    Optional<IdempotencyKey> existing = repo.findById(new IdempotencyKeyId(key, endpoint));
    if (existing.isEmpty()) return IdempotencyResult.fresh();

    IdempotencyKey row = existing.get();
    if (!row.getRequestHash().equals(hash)) return IdempotencyResult.conflict();
    return IdempotencyResult.replay(row.getResponseStatus(), row.getResponseBody());
  }

  @Transactional
  public void store(UUID key, String endpoint, String requestBody,
                    short responseStatus, Map<String, Object> responseBody) {
    OffsetDateTime now = OffsetDateTime.now();
    IdempotencyKey row = IdempotencyKey.builder()
        .idempotencyKey(key)
        .endpoint(endpoint)
        .requestHash(sha256Hex(requestBody))
        .responseStatus(responseStatus)
        .responseBody(responseBody)
        .expiresAt(now.plusHours(config.ttlHours()))
        .build();
    try {
      repo.save(row);
    } catch (DataIntegrityViolationException raceLost) {
      // A concurrent request stored the same key first — the existing row wins.
      log.debug("Lost idempotency-store race for key {} on {}", key, endpoint);
    }
  }

  /**
   * Prune expired rows hourly. The partial index on (expires_at) keeps the scan tiny.
   */
  @Scheduled(fixedDelayString = "PT1H")
  @Transactional
  public void pruneExpired() {
    int removed = repo.deleteExpired(OffsetDateTime.now());
    if (removed > 0) log.info("Pruned {} expired idempotency keys", removed);
  }

  private static String sha256Hex(String body) {
    if (body == null) body = "";
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
