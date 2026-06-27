package com.huylq.iotprojectserver.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

  List<Alert> findByStatusOrderByCreatedAtDesc(Alert.Status status);
}
