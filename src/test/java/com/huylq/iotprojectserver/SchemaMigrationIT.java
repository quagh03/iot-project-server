package com.huylq.iotprojectserver;

import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies Flyway applies cleanly against a fresh Postgres 17 container and that the
 * indexes the design depends on (telemetry hot paths) really exist.
 */
@PostgresIntegrationTest
class SchemaMigrationIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrations_apply_and_all_13_tables_exist() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename",
                String.class);
        assertThat(tables).contains(
                "users", "refresh_tokens",
                "devices", "device_credentials", "device_scopes", "device_health", "sensors",
                "telemetry", "sensor_latest",
                "commands", "rules", "alerts", "audit_logs",
                "idempotency_keys");
    }

    @Test
    void telemetry_hot_path_indexes_exist() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'telemetry'",
                String.class);
        assertThat(indexes).contains("idx_telemetry_sensor_ts", "idx_telemetry_zone_ts");
    }

    @Test
    void seeded_telemetry_partitions_exist() {
        List<String> parts = jdbc.queryForList("""
                SELECT child.relname
                FROM pg_inherits inh
                JOIN pg_class child  ON inh.inhrelid  = child.oid
                JOIN pg_class parent ON inh.inhparent = parent.oid
                WHERE parent.relname = 'telemetry'
                """, String.class);
        assertThat(parts).contains("telemetry_2026_06", "telemetry_2026_07");
    }
}
