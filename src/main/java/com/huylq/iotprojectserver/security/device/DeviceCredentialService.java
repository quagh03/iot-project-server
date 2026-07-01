package com.huylq.iotprojectserver.security.device;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Device credential & scope lifecycle (System Design §9 {@code security/device} module).
 *
 * <p>Owns write access to {@code device_credentials} and {@code device_scopes}. The
 * client secret is <b>write-once</b>: returned on {@link #issue}/{@link #rotate} and
 * never readable afterwards (only metadata is exposed). Rotation keeps the previous
 * secret valid for a grace window so a device can update in place.
 *
 * <p>The registry module calls {@link #revokeForDevice} as the credential side effect
 * of decommissioning — the only place credentials are hard-revoked.
 */
public interface DeviceCredentialService {

  IssuedCredential issue(String deviceId, String callerId, String ip);

  IssuedCredential rotate(String deviceId, String callerId, String ip);

  CredentialMetadata getMetadata(String deviceId);

  List<String> getScopes(String deviceId);

  List<String> replaceScopes(String deviceId, List<String> scopes, String callerId, String ip);

  /**
   * Hard-revoke a device's credential and scopes (decommission side effect). Idempotent —
   * a no-op if the device has no credential. Does not write its own audit entry; the
   * caller audits the owning lifecycle action.
   */
  void revokeForDevice(String deviceId);

  /**
   * The one-time secret view. {@code graceExpiresAt} is non-null only after a rotation.
   */
  record IssuedCredential(String clientId, String clientSecret,
                          OffsetDateTime rotatedAt, OffsetDateTime graceExpiresAt) {
  }

  record CredentialMetadata(String clientId, OffsetDateTime rotatedAt) {
  }
}
