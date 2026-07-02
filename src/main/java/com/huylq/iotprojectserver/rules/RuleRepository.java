package com.huylq.iotprojectserver.rules;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RuleRepository extends JpaRepository<Rule, UUID> {

  /**
   * Rules the engine evaluates — enabled only, ordered by priority (highest first).
   */
  List<Rule> findByEnabledTrueOrderByPriorityDesc();

  Page<Rule> findByEnabled(boolean enabled, Pageable pageable);

  long countByEnabled(boolean enabled);
}
