package com.huylq.iotprojectserver.security.device;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeviceCredentialRepository extends JpaRepository<DeviceCredential, String> {

  Optional<DeviceCredential> findByClientId(String clientId);
}
