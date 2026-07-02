package com.huylq.iotprojectserver.registry;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.security.device.DeviceCredentialService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
class RegistryServiceImpl implements RegistryService {

  private final DeviceRepository deviceRepo;
  private final SensorRepository sensorRepo;
  private final DeviceCredentialService credentialService;
  private final AuditService audit;

  @Override
  @Transactional(readOnly = true)
  public List<Device> list(String zone, Device.Category category, String deviceType,
                           Device.Status status, int offset, int limit) {
    Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
    var page = deviceRepo.findAll(filter(zone, category, deviceType, status),
        PageRequest.of(offset / Math.max(1, limit), limit, sort));
    log.debug("Listed {} devices (zone={} category={} deviceType={} status={} offset={} limit={})",
        page.getNumberOfElements(), zone, category, deviceType, status, offset, limit);
    return page.getContent();
  }

  @Override
  @Transactional(readOnly = true)
  public long count(String zone, Device.Category category, String deviceType, Device.Status status) {
    return deviceRepo.count(filter(zone, category, deviceType, status));
  }

  @Override
  @Transactional
  public Device register(RegisterDeviceCommand cmd, String callerId, String ip) {
    log.info("Registering device '{}' category={} zone={} (caller={})",
        cmd.deviceId(), cmd.category(), cmd.zone(), callerId);
    if (deviceRepo.existsById(cmd.deviceId())) {
      log.warn("Register rejected: deviceId '{}' already exists (caller={})", cmd.deviceId(), callerId);
      throw ApiException.conflict("Device already exists");
    }

    Device parent = resolveParent(cmd.category(), cmd.parentGatewayId());

    String[] protocols = cmd.protocols() == null ? new String[0] : cmd.protocols().toArray(new String[0]);
    Device device = deviceRepo.save(Device.builder()
        .deviceId(cmd.deviceId())
        .category(cmd.category())
        .deviceType(cmd.deviceType())
        .zone(cmd.zone())
        .parentGateway(parent)
        .firmwareVersion(cmd.firmwareVersion())
        .status(Device.Status.INACTIVE)
        .protocols(protocols)
        .build());

    // A sensor device is also mirrored as a sensors row so a gateway's sensor list
    // (GET /devices/{id}/sensors) reflects it without scanning the devices table.
    if (cmd.category() == Device.Category.sensor) {
      sensorRepo.save(Sensor.builder()
          .sensorId(device.getDeviceId())
          .gateway(parent)
          .type(device.getDeviceType())
          .zone(device.getZone())
          .build());
    }

    audit.user(callerId, AuditEvent.DEVICE_REGISTER, device.getDeviceId(),
        Map.of("category", cmd.category().name(), "zone", cmd.zone(), "deviceType", cmd.deviceType()), ip);
    log.info("Device '{}' registered (status=INACTIVE) by caller={}", device.getDeviceId(), callerId);
    return device;
  }

  @Override
  @Transactional(readOnly = true)
  public Device get(String deviceId) {
    return deviceRepo.findById(deviceId)
        .orElseThrow(() -> {
          log.debug("Device {} not found", deviceId);
          return ApiException.notFound("Device not found");
        });
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Device> find(String deviceId) {
    return deviceRepo.findById(deviceId);
  }

  @Override
  @Transactional
  public Device update(String deviceId, String zone, String deviceType, String firmwareVersion,
                       String callerId, String ip) {
    log.info("Updating device '{}' zone={} deviceType={} firmwareVersion={} (caller={})",
        deviceId, zone, deviceType, firmwareVersion, callerId);
    Device device = get(deviceId);
    Map<String, Object> changed = new HashMap<>();
    if (zone != null) {
      device.setZone(zone);
      changed.put("zone", zone);
    }
    if (deviceType != null) {
      device.setDeviceType(deviceType);
      changed.put("deviceType", deviceType);
    }
    if (firmwareVersion != null) {
      device.setFirmwareVersion(firmwareVersion);
      changed.put("firmwareVersion", firmwareVersion);
    }

    // Keep the mirrored sensors row in sync for a sensor device.
    if (device.getCategory() == Device.Category.sensor && (zone != null || deviceType != null)) {
      sensorRepo.findById(deviceId).ifPresent(s -> {
        if (zone != null) s.setZone(zone);
        if (deviceType != null) s.setType(deviceType);
      });
    }

    audit.user(callerId, AuditEvent.DEVICE_UPDATE, deviceId, changed, ip);
    log.info("Device '{}' updated {} by caller={}", deviceId, changed.keySet(), callerId);
    return device;
  }

  @Override
  @Transactional(readOnly = true)
  public List<Sensor> listSensors(String gatewayId) {
    get(gatewayId); // 404 if the gateway itself is unknown
    List<Sensor> sensors = sensorRepo.findByGateway_DeviceId(gatewayId);
    log.debug("Gateway '{}' has {} sensors", gatewayId, sensors.size());
    return sensors;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Sensor> findSensor(String sensorId) {
    return sensorRepo.findById(sensorId);
  }

  @Override
  @Transactional
  public void activate(String deviceId, String callerId, String ip) {
    Device device = get(deviceId);
    transition(device, Device.Status.ACTIVE,
        List.of(Device.Status.INACTIVE, Device.Status.SUSPENDED), "activate");
    audit.user(callerId, AuditEvent.DEVICE_ACTIVATE, deviceId, null, ip);
    log.info("Device '{}' activated by caller={}", deviceId, callerId);
  }

  @Override
  @Transactional
  public void suspend(String deviceId, String callerId, String ip) {
    Device device = get(deviceId);
    transition(device, Device.Status.SUSPENDED, List.of(Device.Status.ACTIVE), "suspend");
    // Token minting is gated on device status == ACTIVE (DeviceTokenService), so the
    // device can no longer obtain new tokens while suspended — reversible via :activate.
    audit.user(callerId, AuditEvent.DEVICE_SUSPEND, deviceId, null, ip);
    log.info("Device '{}' suspended (credential minting disabled) by caller={}", deviceId, callerId);
  }

  @Override
  @Transactional
  public void decommission(String deviceId, String callerId, String ip) {
    Device device = get(deviceId);
    transition(device, Device.Status.DECOMMISSIONED,
        List.of(Device.Status.ACTIVE, Device.Status.INACTIVE, Device.Status.SUSPENDED), "decommission");
    // Terminal: hard-revoke credentials + scopes. Broker topic-ACL revocation lands in Phase 10.
    credentialService.revokeForDevice(deviceId);
    audit.user(callerId, AuditEvent.DEVICE_DECOMMISSION, deviceId, null, ip);
    log.info("Device '{}' decommissioned (credentials revoked) by caller={}", deviceId, callerId);
  }

  private Device resolveParent(Device.Category category, String parentGatewayId) {
    if (category == Device.Category.sensor) {
      if (parentGatewayId == null || parentGatewayId.isBlank()) {
        throw ApiException.unprocessable("A sensor requires a parentGatewayId");
      }
      Device parent = deviceRepo.findById(parentGatewayId)
          .orElseThrow(() -> ApiException.unprocessable("parentGatewayId does not reference a known device"));
      if (parent.getCategory() != Device.Category.gateway) {
        throw ApiException.unprocessable("parentGatewayId must reference a gateway");
      }
      return parent;
    }
    if (parentGatewayId != null && !parentGatewayId.isBlank()) {
      throw ApiException.unprocessable("Only a sensor may have a parentGatewayId");
    }
    return null;
  }

  private static void transition(Device device, Device.Status target,
                                 List<Device.Status> allowedFrom, String action) {
    if (!allowedFrom.contains(device.getStatus())) {
      log.warn("Illegal lifecycle transition: cannot {} device '{}' from {}",
          action, device.getDeviceId(), device.getStatus());
      throw ApiException.invalidLifecycleTransition(
          "Cannot " + action + " a device in status " + device.getStatus());
    }
    device.setStatus(target);
  }

  private static Specification<Device> filter(String zone, Device.Category category,
                                              String deviceType, Device.Status status) {
    return (root, q, cb) -> {
      List<Predicate> preds = new ArrayList<>();
      if (zone != null) preds.add(cb.equal(root.get("zone"), zone));
      if (category != null) preds.add(cb.equal(root.get("category"), category));
      if (deviceType != null) preds.add(cb.equal(root.get("deviceType"), deviceType));
      if (status != null) preds.add(cb.equal(root.get("status"), status));
      return cb.and(preds.toArray(new Predicate[0]));
    };
  }
}
