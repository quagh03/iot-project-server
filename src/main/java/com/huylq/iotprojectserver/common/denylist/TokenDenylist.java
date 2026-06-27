package com.huylq.iotprojectserver.common.denylist;

import java.time.Duration;

/**
 * Revocation denylist for JWTs and refresh tokens.
 *
 * <p>Two key spaces:
 * <ul>
 *   <li><b>access JTI</b> — the {@code jti} claim of an access token. Checked on every
 *       authenticated request by {@code DenylistJwtValidator}, so a logged-out user
 *       can't keep using their access token until natural expiry.</li>
 *   <li><b>refresh hash</b> — SHA-256 of the raw refresh token. Checked by
 *       {@code AuthService.refresh} before the DB lookup; the DB {@code revoked} flag
 *       stays authoritative, this is the fast deny path.</li>
 * </ul>
 *
 * <p>TTLs equal the remaining lifetime of the token, so entries garbage-collect
 * themselves and we never hold them longer than they could be used anyway.
 */
public interface TokenDenylist {

  void blacklistAccessJti(String jti, Duration ttl);

  void blacklistRefreshHash(String hash, Duration ttl);

  boolean isAccessBlacklisted(String jti);

  boolean isRefreshBlacklisted(String hash);
}
