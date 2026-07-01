package com.huylq.iotprojectserver.security.device;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
class DeviceCredentialServiceImpl implements DeviceCredentialService {

  private static final SecureRandom RNG = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

  // Reads the registry's devices table for existence/attachment only — registry remains the
  // sole writer. This module owns device_credentials / device_scopes.
  private final DeviceRepository deviceRepo;
  private final DeviceCredentialRepository credRepo;
  private final DeviceScopeRepository scopeRepo;
  private final PasswordEncoder passwordEncoder;
  private final DeviceCredentialConfig config;
  private final AuditService audit;

  @Override
  @Transactional
  public IssuedCredential issue(String deviceId, String callerId, String ip) {
    log.info("Issuing credential for device '{}' (caller={})", deviceId, callerId);
    Device device = requireActiveOrInactiveDevice(deviceId);
    if (credRepo.existsById(deviceId)) {
      log.warn("Credential issue rejected: device '{}' already has a credential (use rotate)", deviceId);
      throw ApiException.conflict("Device already has a credential; rotate instead");
    }

    String clientId = generateClientId();
    String secret = generateSecret();
    OffsetDateTime now = Clocks.nowUtc();
    credRepo.save(DeviceCredential.builder()
        .device(device)
        .clientId(clientId)
        .clientSecretHash(passwordEncoder.encode(secret))
        .rotatedAt(now)
        .build());

    audit.user(callerId, AuditEvent.DEVICE_CREDENTIAL_ISSUE, deviceId, Map.of("clientId", clientId), ip);
    log.info("Credential issued for device '{}' clientId='{}' (secret shown once) by caller={}",
        deviceId, clientId, callerId);
    return new IssuedCredential(clientId, secret, now, null);
  }

  @Override
  @Transactional
  public IssuedCredential rotate(String deviceId, String callerId, String ip) {
    log.info("Rotating credential for device '{}' (caller={})", deviceId, callerId);
    DeviceCredential cred = credRepo.findById(deviceId)
        .orElseThrow(() -> {
          log.warn("Rotate rejected: device '{}' has no credential", deviceId);
          return ApiException.notFound("Device has no credential to rotate");
        });

    String secret = generateSecret();
    OffsetDateTime now = Clocks.nowUtc();
    OffsetDateTime graceExpiresAt = now.plus(config.credentialRotationGrace());
    // Old secret stays valid until the grace window closes (DeviceTokenService honours it).
    cred.setPreviousSecretHash(cred.getClientSecretHash());
    cred.setGraceExpiresAt(graceExpiresAt);
    cred.setClientSecretHash(passwordEncoder.encode(secret));
    cred.setRotatedAt(now);

    audit.user(callerId, AuditEvent.DEVICE_CREDENTIAL_ROTATE, deviceId,
        Map.of("clientId", cred.getClientId(), "graceExpiresAt", graceExpiresAt.toString()), ip);
    log.info("Credential rotated for device '{}' clientId='{}' graceExpiresAt={} by caller={}",
        deviceId, cred.getClientId(), graceExpiresAt, callerId);
    return new IssuedCredential(cred.getClientId(), secret, now, graceExpiresAt);
  }

  @Override
  @Transactional(readOnly = true)
  public CredentialMetadata getMetadata(String deviceId) {
    DeviceCredential cred = credRepo.findById(deviceId)
        .orElseThrow(() -> ApiException.notFound("Device has no credential"));
    return new CredentialMetadata(cred.getClientId(), cred.getRotatedAt());
  }

  @Override
  @Transactional(readOnly = true)
  public List<String> getScopes(String deviceId) {
    requireDevice(deviceId);
    return scopeRepo.findByDeviceId(deviceId).stream().map(DeviceScope::getScope).toList();
  }

  @Override
  @Transactional
  public List<String> replaceScopes(String deviceId, List<String> scopes, String callerId, String ip) {
    log.info("Replacing scopes for device '{}' -> {} (caller={})", deviceId, scopes, callerId);
    requireDevice(deviceId);
    Set<String> validated = validateAndDedupe(scopes);

    scopeRepo.deleteByDeviceId(deviceId);
    scopeRepo.flush(); // settle the delete before re-inserting the same composite PKs
    validated.forEach(scope ->
        scopeRepo.save(DeviceScope.builder().deviceId(deviceId).scope(scope).build()));

    audit.user(callerId, AuditEvent.DEVICE_SCOPES_REPLACE, deviceId,
        Map.of("scopes", List.copyOf(validated)), ip);
    log.info("Scopes for device '{}' replaced with {} by caller={}", deviceId, validated, callerId);
    return List.copyOf(validated);
  }

  @Override
  @Transactional
  public void revokeForDevice(String deviceId) {
    scopeRepo.deleteByDeviceId(deviceId);
    if (credRepo.existsById(deviceId)) {
      credRepo.deleteById(deviceId);
      log.info("Revoked credential + scopes for device '{}'", deviceId);
    } else {
      log.debug("No credential to revoke for device '{}'", deviceId);
    }
  }

  private Device requireDevice(String deviceId) {
    return deviceRepo.findById(deviceId)
        .orElseThrow(() -> ApiException.notFound("Device not found"));
  }

  private Device requireActiveOrInactiveDevice(String deviceId) {
    Device device = requireDevice(deviceId);
    if (device.getStatus() == Device.Status.DECOMMISSIONED) {
      throw ApiException.unprocessable("Cannot issue credentials for a decommissioned device");
    }
    return device;
  }

  private static Set<String> validateAndDedupe(List<String> scopes) {
    Set<String> out = new LinkedHashSet<>();
    if (scopes == null) return out;
    for (String raw : scopes) {
      try {
        out.add(DeviceScope.Scope.fromDbValue(raw).getDbValue());
      } catch (IllegalArgumentException e) {
        throw ApiException.unprocessable("Unknown scope: " + raw);
      }
    }
    return out;
  }

  private static String generateClientId() {
    byte[] bytes = new byte[12];
    RNG.nextBytes(bytes);
    return "cli_" + B64.encodeToString(bytes);
  }

  private static String generateSecret() {
    byte[] bytes = new byte[32];
    RNG.nextBytes(bytes);
    return B64.encodeToString(bytes);
  }
}
