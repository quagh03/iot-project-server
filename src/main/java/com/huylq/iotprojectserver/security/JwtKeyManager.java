package com.huylq.iotprojectserver.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Builds the active signing key and the full verification key set (active + retired) from
 * {@link JwtKeyProperties}. No dependency on a real KMS SDK — that integration is a
 * deployment-time concern (see the ops runbook); this class only needs PEM material or,
 * as a dev/test fallback, generates one itself.
 */
@Slf4j
@Component
public class JwtKeyManager {

  private final RSAKey activeSigningKey;
  private final JWKSet publicJwkSet;

  public JwtKeyManager(JwtKeyProperties props) {
    this.activeSigningKey = buildActiveKey(props);
    List<JWK> publicKeys = new ArrayList<>();
    publicKeys.add(activeSigningKey.toPublicJWK());
    for (JwtKeyProperties.RetiredKey retired : props.retiredKeys()) {
      publicKeys.add(buildKey(retired.kid(), parsePublicKey(retired.publicKeyPem()), null));
    }
    this.publicJwkSet = new JWKSet(publicKeys);
    log.info("JWT key set loaded: active kid={}, {} retired key(s)", activeSigningKey.getKeyID(),
        props.retiredKeys().size());
  }

  /** Includes the private key — only for the {@code JwtEncoder}, never exposed on the wire. */
  public RSAKey activeSigningKey() {
    return activeSigningKey;
  }

  public String activeKid() {
    return activeSigningKey.getKeyID();
  }

  /** Public-only view (active + retired) — safe for the JWKS endpoint and the decoder. */
  public JWKSet publicJwkSet() {
    return publicJwkSet;
  }

  private static RSAKey buildActiveKey(JwtKeyProperties props) {
    if (props.activePrivateKeyPem() != null && !props.activePrivateKeyPem().isBlank()
        && props.activePublicKeyPem() != null && !props.activePublicKeyPem().isBlank()) {
      String kid = (props.activeKid() == null || props.activeKid().isBlank())
          ? "active" : props.activeKid();
      return buildKey(kid, parsePublicKey(props.activePublicKeyPem()), parsePrivateKey(props.activePrivateKeyPem()));
    }
    log.warn("No iot.security.jwt.keys.active-private-key-pem/active-public-key-pem configured — "
        + "generating an EPHEMERAL RSA keypair for this process. Every token issued before a restart "
        + "or across instances becomes unverifiable. Acceptable ONLY for local/test profiles — "
        + "production must supply a real KMS-backed key (see the ops runbook).");
    return generateEphemeralKey();
  }

  private static RSAKey buildKey(String kid, RSAPublicKey publicKey, RSAPrivateKey privateKey) {
    RSAKey.Builder builder = new RSAKey.Builder(publicKey)
        .keyID(kid)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256);
    if (privateKey != null) builder = builder.privateKey(privateKey);
    return builder.build();
  }

  private static RSAKey generateEphemeralKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      String kid = "ephemeral-" + UUID.randomUUID();
      return buildKey(kid, (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("RSA key generation unavailable", e);
    }
  }

  private static RSAPrivateKey parsePrivateKey(String pem) {
    try {
      byte[] der = Base64.getDecoder().decode(stripPem(pem, "PRIVATE KEY"));
      KeyFactory factory = KeyFactory.getInstance("RSA");
      return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid RSA private key PEM (expected PKCS8)", e);
    }
  }

  private static RSAPublicKey parsePublicKey(String pem) {
    try {
      byte[] der = Base64.getDecoder().decode(stripPem(pem, "PUBLIC KEY"));
      KeyFactory factory = KeyFactory.getInstance("RSA");
      return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
    } catch (NoSuchAlgorithmException | InvalidKeySpecException | IllegalArgumentException e) {
      throw new IllegalStateException("Invalid RSA public key PEM (expected X.509/SPKI)", e);
    }
  }

  private static String stripPem(String pem, String label) {
    return pem.replace("-----BEGIN " + label + "-----", "")
        .replace("-----END " + label + "-----", "")
        .replaceAll("\\s", "");
  }
}
