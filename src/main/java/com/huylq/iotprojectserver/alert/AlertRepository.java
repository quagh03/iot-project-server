package com.huylq.iotprojectserver.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

  List<Alert> findByStatusOrderByCreatedAtDesc(Alert.Status status);

  /**
   * Backs {@link AlertService}'s {@code OpenAlertQuery} — the safety-interlock signal
   * (command module) asks "is there an unresolved hazard in this zone of one of these
   * types" without needing to load full {@link Alert} rows.
   */
  boolean existsByZoneAndTypeInAndStatus(String zone, Collection<String> types, Alert.Status status);
}
