package com.huylq.iotprojectserver.registry;

import java.util.List;
import java.util.Optional;

/**
 * Device registry & lifecycle (System Design §9 {@code registry} module).
 *
 * <p>Owns write access to the {@code devices} and {@code sensors} tables. Lifecycle
 * transitions are explicit named actions — there is no free-form status setter — so
 * each transition can carry its own side effects and audit entry. Credential/topic-ACL
 * side effects on suspend/decommission are delegated to the {@code security/device}
 * module through its published service interface.
 */
public interface RegistryService {

  List<Device> list(String zone, Device.Category category, String deviceType,
                    Device.Status status, int offset, int limit);

  long count(String zone, Device.Category category, String deviceType, Device.Status status);

  Device register(RegisterDeviceCommand cmd, String callerId, String ip);

  Device get(String deviceId);

  /**
   * Registry-derived lookup for ingest/command-time validation (e.g. {@code command}'s
   * target resolution) — {@link Optional#empty()} rather than a 404, since callers
   * outside this module treat "unknown device" as their own validation failure.
   */
  Optional<Device> find(String deviceId);

  Device update(String deviceId, String zone, String deviceType, String firmwareVersion,
                String callerId, String ip);

  List<Sensor> listSensors(String gatewayId);

  /**
   * Registry-derived lookup for ingest-time validation (e.g. {@code telemetry}'s
   * sensorType/gateway cross-check) — {@link Optional#empty()} rather than a 404, since
   * callers outside this module treat "unknown sensor" as their own validation failure.
   */
  Optional<Sensor> findSensor(String sensorId);

  void activate(String deviceId, String callerId, String ip);

  void suspend(String deviceId, String callerId, String ip);

  void decommission(String deviceId, String callerId, String ip);

  /**
   * Registration inputs, decoupled from the wire DTO so the registry module does not
   * depend on the {@code api} layer.
   */
  record RegisterDeviceCommand(
      String deviceId,
      Device.Category category,
      String deviceType,
      String zone,
      String parentGatewayId,
      String firmwareVersion,
      List<String> protocols) {
  }
}
