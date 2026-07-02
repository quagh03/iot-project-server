package com.huylq.iotprojectserver.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtKeyManagerTest {

  @Test
  void generates_an_ephemeral_rsa_key_when_none_configured() {
    JwtKeyManager manager = new JwtKeyManager(new JwtKeyProperties(null, null, null, null));

    assertThat(manager.activeSigningKey().isPrivate()).isTrue();
    assertThat(manager.activeSigningKey().size()).isEqualTo(2048);
    assertThat(manager.activeKid()).startsWith("ephemeral-");
  }

  @Test
  void uses_the_configured_active_key_and_kid() throws Exception {
    RSAKey key = generateRsaKey("kid-active");

    JwtKeyManager manager = new JwtKeyManager(new JwtKeyProperties(
        "kid-active", toPkcs8Pem(key), toX509Pem(key.toRSAPublicKey()), null));

    assertThat(manager.activeKid()).isEqualTo("kid-active");
    assertThat(manager.activeSigningKey().isPrivate()).isTrue();
  }

  @Test
  void public_jwk_set_never_exposes_private_key_material() throws Exception {
    RSAKey active = generateRsaKey("active");
    RSAKey retired = generateRsaKey("retired");

    JwtKeyManager manager = new JwtKeyManager(new JwtKeyProperties(
        "active", toPkcs8Pem(active), toX509Pem(active.toRSAPublicKey()),
        List.of(new JwtKeyProperties.RetiredKey("retired", toX509Pem(retired.toRSAPublicKey())))));

    List<JWK> keys = manager.publicJwkSet().getKeys();
    assertThat(keys).hasSize(2).allSatisfy(k -> assertThat(k.isPrivate()).isFalse());
    assertThat(keys.stream().map(JWK::getKeyID).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("active", "retired");
  }

  @Test
  void a_token_signed_by_a_retired_key_still_verifies_via_the_published_key_set() throws Exception {
    // Simulates the exact rollover guarantee (§7): the OLD active key is demoted to
    // "retired" once a NEW key takes over signing, but a token it already issued must
    // keep verifying by kid until it naturally expires.
    RSAKey oldKeyNowRetired = generateRsaKey("2026-01");
    RSAKey newActiveKey = generateRsaKey("2026-02");

    JwtKeyManager manager = new JwtKeyManager(new JwtKeyProperties(
        "2026-02", toPkcs8Pem(newActiveKey), toX509Pem(newActiveKey.toRSAPublicKey()),
        List.of(new JwtKeyProperties.RetiredKey("2026-01", toX509Pem(oldKeyNowRetired.toRSAPublicKey())))));

    String tokenSignedByOldKey = signToken(oldKeyNowRetired, "2026-01");

    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
        JWSAlgorithm.RS256, new ImmutableJWKSet<>(manager.publicJwkSet())));

    JWTClaimsSet claims = processor.process(tokenSignedByOldKey, null);
    assertThat(claims.getSubject()).isEqualTo("user-1");
  }

  @Test
  void a_token_signed_by_a_key_outside_the_published_set_fails_to_verify() throws Exception {
    RSAKey trustedKey = generateRsaKey("trusted");
    RSAKey unknownKey = generateRsaKey("unknown");

    JwtKeyManager manager = new JwtKeyManager(new JwtKeyProperties(
        "trusted", toPkcs8Pem(trustedKey), toX509Pem(trustedKey.toRSAPublicKey()), null));

    String tokenSignedByUnknownKey = signToken(unknownKey, "unknown");

    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
        JWSAlgorithm.RS256, new ImmutableJWKSet<>(manager.publicJwkSet())));

    assertThatThrownBy(() -> processor.process(tokenSignedByUnknownKey, null))
        .isInstanceOf(Exception.class);
  }

  private static RSAKey generateRsaKey(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
        .privateKey((RSAPrivateKey) pair.getPrivate())
        .keyID(kid)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .build();
  }

  private static String signToken(RSAKey key, String kid) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().subject("user-1").build();
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims);
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }

  private static String toPkcs8Pem(RSAKey key) throws Exception {
    byte[] der = key.toRSAPrivateKey().getEncoded();
    return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
        .encodeToString(der) + "\n-----END PRIVATE KEY-----";
  }

  private static String toX509Pem(RSAPublicKey key) {
    byte[] der = key.getEncoded();
    return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes())
        .encodeToString(der) + "\n-----END PUBLIC KEY-----";
  }
}
