package com.huylq.iotprojectserver.security.user;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.denylist.TokenDenylist;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.error.ErrorType;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.security.JwtConfig;
import com.huylq.iotprojectserver.security.JwtService;
import com.huylq.iotprojectserver.security.detection.SecurityDetectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
class AuthServiceImpl implements AuthService {

  private final UserRepository userRepo;
  private final RefreshTokenRepository refreshRepo;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final JwtConfig jwtConfig;
  private final AuditService audit;
  private final TokenDenylist denylist;
  private final SecurityDetectionService securityDetection;

  @Override
  @Transactional
  public IssuedTokens login(String username, String password, String ip) {
    long start = System.currentTimeMillis();
    log.info("Authenticating user '{}'", username);
    Optional<User> maybeUser = userRepo.findByUsername(username);
    if (maybeUser.isEmpty() || maybeUser.get().getStatus() != User.Status.ACTIVE
        || !passwordEncoder.matches(password, maybeUser.get().getPasswordHash())) {
      // Identical 401 for all failure modes — never leak which side failed.
      log.warn("Login failed for username='{}'", username);
      audit.append(username, AuditLog.ActorType.USER,
          AuditEvent.USER_LOGIN_FAILED, null, null, ip);
      securityDetection.recordAuthFailure(username, ip);
      throw new ApiException(ErrorType.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }
    User user = maybeUser.get();
    IssuedTokens tokens = issueTokens(user);
    audit.user(user.getId().toString(), AuditEvent.USER_LOGIN, username, null, ip);
    log.info("User '{}' (id={}) logged in in {}ms",
        username, user.getId(), System.currentTimeMillis() - start);
    return tokens;
  }

  @Override
  @Transactional(noRollbackFor = ApiException.class)
  public IssuedTokens refresh(String refreshToken, String ip) {
    String hash = TokenHashes.sha256(refreshToken);

    // Fast-deny path — Redis (or in-memory) denylist short-circuits the DB read.
    // We still walk the chain (defense in depth) so any descendants get revoked too.
    if (denylist.isRefreshBlacklisted(hash)) {
      log.warn("Refresh rejected: blacklisted token presented — cascade-revoking chain");
      refreshRepo.findByTokenHash(hash).ifPresent(this::cascadeRevoke);
      throw ApiException.tokenRevoked("Refresh token already used");
    }

    RefreshToken row = refreshRepo.findByTokenHash(hash)
        .orElseThrow(() -> new ApiException(ErrorType.UNAUTHENTICATED,
            HttpStatus.UNAUTHORIZED, "Refresh token not recognized"));

    OffsetDateTime now = Clocks.nowUtc();
    if (Boolean.TRUE.equals(row.getRevoked())) {
      // Reuse of a revoked token signals a potentially compromised refresh chain.
      // Cascade-revoke + denylist the chain so an attacker holding any of them loses it too.
      log.warn("Refresh token reuse detected for user {} (tokenId={}) — cascade-revoking chain",
          row.getUser().getId(), row.getId());
      cascadeRevoke(row);
      audit.user(row.getUser().getId().toString(), AuditEvent.USER_TOKEN_REUSE_DETECTED,
          null, Map.of("tokenId", row.getId().toString()), ip);
      securityDetection.recordRefreshReuse(row.getUser().getId().toString());
      throw ApiException.tokenRevoked("Refresh token already used");
    }
    if (row.getExpiresAt().isBefore(now)) {
      log.info("Refresh rejected: expired token (tokenId={}) for user {}",
          row.getId(), row.getUser().getId());
      throw new ApiException(ErrorType.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, "Refresh token expired");
    }

    User user = row.getUser();
    IssuedTokens issued = issueTokens(user);
    RefreshToken newRow = refreshRepo.findByTokenHash(TokenHashes.sha256(issued.refreshToken()))
        .orElseThrow();
    row.setRevoked(true);
    row.setRotatedTo(newRow);
    // The just-rotated token must never be replayed.
    denylist.blacklistRefreshHash(row.getTokenHash(), remainingLifetime(row.getExpiresAt()));

    audit.user(user.getId().toString(), AuditEvent.USER_TOKEN_ROTATED,
        null, Map.of("oldTokenId", row.getId().toString(),
            "newTokenId", newRow.getId().toString()), ip);
    log.info("Refresh token rotated for user {} (oldTokenId={} -> newTokenId={})",
        user.getId(), row.getId(), newRow.getId());
    return issued;
  }

  @Override
  @Transactional
  public void logout(String refreshToken, String ip) {
    // Always blacklist + try to revoke regardless of DB state, so 204 is idempotent.
    if (refreshToken != null && !refreshToken.isBlank()) {
      String hash = TokenHashes.sha256(refreshToken);
      refreshRepo.findByTokenHash(hash).ifPresent(row -> {
        row.setRevoked(true);
        denylist.blacklistRefreshHash(hash, remainingLifetime(row.getExpiresAt()));
        audit.user(row.getUser().getId().toString(), AuditEvent.USER_LOGOUT,
            null, Map.of("tokenId", row.getId().toString()), ip);
        log.info("User {} logged out (tokenId={} revoked)",
            row.getUser().getId(), row.getId());
      });
    }
    // If the caller is logging out with a valid access token, kill it now too — don't
    // wait the full TTL. SecurityContext is populated by the oauth2 resource server
    // filter; absent if the caller didn't send an Authorization header.
    Jwt accessJwt = currentAccessJwt();
    if (accessJwt != null && accessJwt.getId() != null && accessJwt.getExpiresAt() != null) {
      denylist.blacklistAccessJti(accessJwt.getId(),
          Duration.between(Instant.now(), accessJwt.getExpiresAt()));
    }
  }

  private IssuedTokens issueTokens(User user) {
    String access = jwtService.issueUserAccessToken(user.getId().toString(), user.getRole().name());
    String refresh = UUID.randomUUID().toString();

    List<RefreshToken> refreshTokens = refreshRepo.findAllActiveByUserId(user.getId(), Clocks.nowUtc());
    if (!refreshTokens.isEmpty()) {
      CompletableFuture.runAsync(() -> refreshTokens.forEach(row -> {
        denylist.blacklistRefreshHash(row.getTokenHash(), remainingLifetime(row.getExpiresAt()));
      }));
      refreshRepo.revokeAllForUser(user.getId());
    }

    RefreshToken row = RefreshToken.builder()
        .user(user)
        .tokenHash(TokenHashes.sha256(refresh))
        .expiresAt(Clocks.nowUtc().plus(jwtConfig.refreshTokenTtl()))
        .revoked(false)
        .build();
    refreshRepo.save(row);

    return new IssuedTokens(access, jwtConfig.accessTokenTtl().getSeconds(),
        refresh, user.getRole().name());
  }

  private void cascadeRevoke(RefreshToken start) {
    RefreshToken cursor = start;
    while (cursor != null) {
      cursor.setRevoked(true);
      denylist.blacklistRefreshHash(cursor.getTokenHash(), remainingLifetime(cursor.getExpiresAt()));
      cursor = cursor.getRotatedTo();
    }
  }

  private static Duration remainingLifetime(OffsetDateTime expiry) {
    Duration d = Duration.between(Instant.now(), expiry.toInstant());
    return d.isNegative() ? Duration.ZERO : d;
  }

  private static Jwt currentAccessJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken t) return t.getToken();
    return null;
  }
}
