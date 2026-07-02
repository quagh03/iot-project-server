package com.huylq.iotprojectserver.security;

import com.huylq.iotprojectserver.common.time.Clocks;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Issues signed JWTs. Verification uses Spring's {@code JwtDecoder} bean.
 *
 * <p>Claim layout:
 * <ul>
 *   <li>{@code sub} — subject (user id or device id)</li>
 *   <li>{@code typ} — {@code "USER"} or {@code "DEVICE"} (custom claim, our switch)</li>
 *   <li>{@code role} — for user tokens only (e.g. {@code "OPERATOR"})</li>
 *   <li>{@code scope} — space-separated granted scopes for device tokens (OAuth2 standard)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class JwtService {

  public static final String TYPE_USER = "USER";
  public static final String TYPE_DEVICE = "DEVICE";

  private final JwtEncoder encoder;
  private final JwtConfig config;
  private final JwtKeyManager keyManager;

  public String issueUserAccessToken(String userId, String role) {
    Instant now = Clocks.nowUtc().toInstant();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(config.issuer())
        .id(UUID.randomUUID().toString())   // jti — needed for denylist revocation
        .issuedAt(now)
        .expiresAt(now.plus(config.accessTokenTtl()))
        .subject(userId)
        .claim("typ", TYPE_USER)
        .claim("role", role)
        .build();
    return encode(claims);
  }

  public String issueDeviceToken(String deviceId, Collection<String> grantedScopes) {
    Instant now = Clocks.nowUtc().toInstant();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(config.issuer())
        .id(UUID.randomUUID().toString())
        .issuedAt(now)
        .expiresAt(now.plus(config.deviceTokenTtl()))
        .subject(deviceId)
        .claim("typ", TYPE_DEVICE)
        .claim("scope", String.join(" ", grantedScopes))
        .build();
    return encode(claims);
  }

  public Map<String, Object> peekClaims(String token) {
    return Map.of(); // not used here; verification goes through the JwtDecoder bean
  }

  private String encode(JwtClaimsSet claims) {
    JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyManager.activeKid()).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }
}
