package com.huylq.iotprojectserver.health;

import com.huylq.iotprojectserver.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
class HealthServiceImpl implements HealthService {

  private final DeviceHealthRepository repo;

  @Override
  @Transactional(readOnly = true)
  public List<ZoneConnectivityRow> connectivity(String zone) {
    return repo.rollUpByZone(zone);
  }

  @Override
  @Transactional
  public void upsertHeartbeat(HeartbeatCommand command) {
    if (command.authenticatedDeviceId() != null
        && !command.authenticatedDeviceId().equals(command.deviceId())) {
      log.warn("Heartbeat identity mismatch: token={} payload.deviceId={}",
          command.authenticatedDeviceId(), command.deviceId());
      throw ApiException.forbidden("deviceId does not match the authenticated device identity");
    }
    repo.upsert(command.deviceId(), DeviceHealth.ConnectionStatus.ONLINE.name(), command.ts(),
        command.memoryUsagePct(), command.cpuUsagePct(), command.wifiRssi());
  }

  @Override
  @Transactional
  public void touchOnline(String deviceId, OffsetDateTime lastSeen) {
    repo.touchOnline(deviceId, lastSeen);
  }

  @Override
  @Transactional
  public void markOffline(String deviceId) {
    repo.markOffline(deviceId);
  }

  @Override
  @Transactional(readOnly = true)
  public DeviceHealth getHealth(String deviceId) {
    return repo.findById(deviceId)
        .orElseThrow(() -> ApiException.notFound("No health record for device " + deviceId));
  }
}
