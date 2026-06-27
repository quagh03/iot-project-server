package com.huylq.iotprojectserver.registry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, String>, JpaSpecificationExecutor<Device> {

  List<Device> findByZone(String zone);

  List<Device> findByCategory(Device.Category category);

  List<Device> findByParentGateway_DeviceId(String parentGatewayId);
}
