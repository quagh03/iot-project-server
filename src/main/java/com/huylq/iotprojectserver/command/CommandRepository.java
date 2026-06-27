package com.huylq.iotprojectserver.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface CommandRepository extends JpaRepository<Command, String>, JpaSpecificationExecutor<Command> {

  List<Command> findByTarget_DeviceIdOrderByIssuedAtDesc(String targetId);

  @Query("""
      SELECT c FROM Command c
      WHERE c.status IN (
          com.huylq.iotprojectserver.command.Command.Status.PENDING,
          com.huylq.iotprojectserver.command.Command.Status.RECEIVED
      ) AND c.issuedAt < :cutoff
      """)
  List<Command> findOpenIssuedBefore(@Param("cutoff") OffsetDateTime cutoff);
}
