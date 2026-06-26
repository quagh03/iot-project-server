package com.huylq.iotprojectserver.audit;

import com.huylq.iotprojectserver.audit.AuditLog;
import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@PostgresIntegrationTest
class AuditServiceIT {

    @Autowired
    AuditService audit;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appends_entry_to_partitioned_audit_logs() {
        audit.user("usr_8f3a", AuditEvent.USER_LOGIN, "/v1/auth/login",
                Map.of("ua", "test-agent"), "127.0.0.1");

        Integer count = jdbc.queryForObject(
                "SELECT count(*)::int FROM audit_logs WHERE actor = 'usr_8f3a' AND event = ?",
                Integer.class, AuditEvent.USER_LOGIN.code());
        assertThat(count).isEqualTo(1);

        String actorType = jdbc.queryForObject(
                "SELECT actor_type FROM audit_logs WHERE actor = 'usr_8f3a' LIMIT 1",
                String.class);
        assertThat(actorType).isEqualTo(AuditLog.ActorType.USER.name());
    }

    @Test
    void system_appends_use_system_actor_type() {
        audit.system(AuditEvent.PARTITION_CREATED, "telemetry_2099_01",
                Map.of("table", "telemetry", "month", "2099-01"));

        String actorType = jdbc.queryForObject(
                "SELECT actor_type FROM audit_logs WHERE event = ? LIMIT 1",
                String.class, AuditEvent.PARTITION_CREATED.code());
        assertThat(actorType).isEqualTo(AuditLog.ActorType.SYSTEM.name());
    }
}
