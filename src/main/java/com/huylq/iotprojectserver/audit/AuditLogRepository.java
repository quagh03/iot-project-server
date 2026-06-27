package com.huylq.iotprojectserver.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, AuditLogId>,
    JpaSpecificationExecutor<AuditLog> {
}
