package com.huylq.iotprojectserver.alert;

import com.huylq.iotprojectserver.registry.RegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
class AlertServiceImpl implements AlertService {

  private final AlertRepository repo;
  private final RegistryService registry;

  @Override
  @Transactional
  public Alert raise(String type, Alert.Severity severity, String zone, String sourceDeviceId, String message) {
    Alert alert = Alert.builder()
        .type(type)
        .severity(severity)
        .zone(zone)
        .sourceDevice(sourceDeviceId == null ? null : registry.find(sourceDeviceId).orElse(null))
        .message(message)
        .status(Alert.Status.OPEN)
        .build();
    alert = repo.save(alert);
    log.info("Alert raised: id={} type={} severity={} zone={} sourceDeviceId={}",
        alert.getId(), type, severity, zone, sourceDeviceId);
    return alert;
  }
}
