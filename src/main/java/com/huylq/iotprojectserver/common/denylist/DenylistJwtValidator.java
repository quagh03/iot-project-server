package com.huylq.iotprojectserver.common.denylist;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Rejects any JWT whose {@code jti} is in the denylist. Chained with the default
 * timestamp/issuer validators inside {@code SecurityConfig.jwtDecoder}.
 */
@Component
@RequiredArgsConstructor
public class DenylistJwtValidator implements OAuth2TokenValidator<Jwt> {

  private final TokenDenylist denylist;

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String jti = token.getId();
    if (jti != null && denylist.isAccessBlacklisted(jti)) {
      return OAuth2TokenValidatorResult.failure(new OAuth2Error(
          "invalid_token", "Token has been revoked", null));
    }
    return OAuth2TokenValidatorResult.success();
  }
}
