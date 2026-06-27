package com.huylq.iotprojectserver.security.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hash for refresh-token storage. Refresh tokens themselves are random UUIDs;
 * we never store the raw value, only this hex digest.
 */
final class TokenHashes {

  private TokenHashes() {
  }

  static String sha256(String raw) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
