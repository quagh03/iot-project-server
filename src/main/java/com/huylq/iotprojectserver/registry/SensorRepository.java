package com.huylq.iotprojectserver.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SensorRepository extends JpaRepository<Sensor, String> {

  List<Sensor> findByGateway_DeviceId(String gatewayId);

  List<Sensor> findByZone(String zone);
}
