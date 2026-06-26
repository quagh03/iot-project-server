package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@PostgresIntegrationTest
class SensorLatestUpsertIT {

    @Autowired
    SensorLatestRepository repo;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @Transactional
    void newer_ts_replaces_value_but_older_ts_is_dropped() {
        String sensorId = "s_upsert_test";
        OffsetDateTime older = OffsetDateTime.of(2026, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime newer = OffsetDateTime.of(2026, 6, 1, 10, 5, 0, 0, ZoneOffset.UTC);

        repo.upsert(sensorId, "office_1", "temp", 22.4, null, "C", older);
        Double v1 = jdbc.queryForObject("SELECT value_num FROM sensor_latest WHERE sensor_id = ?",
                Double.class, sensorId);
        assertThat(v1).isEqualTo(22.4);

        // Newer ts overrides
        repo.upsert(sensorId, "office_1", "temp", 23.7, null, "C", newer);
        Double v2 = jdbc.queryForObject("SELECT value_num FROM sensor_latest WHERE sensor_id = ?",
                Double.class, sensorId);
        assertThat(v2).isEqualTo(23.7);

        // Older ts must NOT override (out-of-order guard from the migration comment).
        repo.upsert(sensorId, "office_1", "temp", 99.9, null, "C", older);
        Double v3 = jdbc.queryForObject("SELECT value_num FROM sensor_latest WHERE sensor_id = ?",
                Double.class, sensorId);
        assertThat(v3).isEqualTo(23.7);
    }
}
