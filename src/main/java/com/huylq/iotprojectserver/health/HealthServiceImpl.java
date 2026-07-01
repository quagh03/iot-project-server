package com.huylq.iotprojectserver.health;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
class HealthServiceImpl implements HealthService {

  private final DeviceHealthRepository repo;

  @Override
  @Transactional(readOnly = true)
  public List<ZoneConnectivityRow> connectivity(String zone) {
    return repo.rollUpByZone(zone);
  }
}
