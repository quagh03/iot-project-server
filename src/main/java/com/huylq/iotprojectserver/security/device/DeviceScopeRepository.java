package com.huylq.iotprojectserver.security.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceScopeRepository extends JpaRepository<DeviceScope, DeviceScopeId> {

  List<DeviceScope> findByDeviceId(String deviceId);

  void deleteByDeviceId(String deviceId);
}
