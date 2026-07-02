package com.huylq.iotprojectserver.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Asymmetric JWT signing key configuration (§7 secrets table: KMS-managed signing key,
 * scheduled rotation, {@code kid}-based key-rollover). In production these three values
 * come from a KMS/secrets manager at runtime, never from source — see the ops runbook
 * for the rotation procedure. {@code retiredKeys} lets a rolled-over key keep verifying
 * (never signing) tokens it already issued until they naturally expire.
 *
 * <p>When {@code activePrivateKeyPem}/{@code activePublicKeyPem} are unset, {@link
 * JwtKeyManager} generates an ephemeral in-process RSA keypair instead — acceptable only
 * for {@code local}/{@code test}, since every previously issued token becomes
 * unverifiable on the next restart. Production must always configure real keys.
 */
@ConfigurationProperties("iot.security.jwt.keys")
public record JwtKeyProperties(
    String activeKid,
    String activePrivateKeyPem,
    String activePublicKeyPem,
    List<RetiredKey> retiredKeys) {

  public JwtKeyProperties {
    if (retiredKeys == null) retiredKeys = List.of();
  }

  public record RetiredKey(String kid, String publicKeyPem) {
  }
}
