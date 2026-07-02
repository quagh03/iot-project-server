package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.security.JwtKeyManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public JWK Set (active + retired verification keys) — the standard
 * mechanism a key-rollover scheme needs so any relying party can resolve a token's {@code
 * kid} to the right public key without redeploying (§7 secrets table). Public, unauthenticated
 * endpoint — a JWKS contains no private key material by construction ({@code
 * JwtKeyManager.publicJwkSet()} never includes one).
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

  private final JwtKeyManager keyManager;

  @GetMapping("/api/v1/.well-known/jwks.json")
  public ResponseEntity<Map<String, Object>> jwks() {
    return ResponseEntity.ok(keyManager.publicJwkSet().toJSONObject());
  }
}
