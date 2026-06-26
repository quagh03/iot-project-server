package com.huylq.iotprojectserver.common.partition;

import com.huylq.iotprojectserver.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@PostgresIntegrationTest
class PartitionManagerIT {

    @Autowired
    PartitionManager partitionManager;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void creates_future_partition_idempotently() {
        YearMonth future = YearMonth.of(2099, 12);
        String childName = "telemetry_2099_12";

        partitionManager.ensurePartition("telemetry", future);
        partitionManager.ensurePartition("telemetry", future);  // second call must not fail

        List<String> parts = partitionManager.listPartitions("telemetry");
        assertThat(parts).contains(childName);
    }

    @Test
    void inserted_telemetry_row_routes_to_correct_partition() {
        // Use a month we explicitly seed so this test is deterministic.
        YearMonth target = YearMonth.of(2026, 6);
        partitionManager.ensurePartition("telemetry", target);

        jdbc.update("""
                INSERT INTO telemetry (ts, zone, gateway_id, sensor_id, sensor_type, value_num, unit)
                VALUES ('2026-06-15 10:00:00+00', 'office_1', 'gw_office1_01', 's_temp_partition_test', 'temp', 22.4, 'C')
                """);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*)::int FROM telemetry_2026_06 WHERE sensor_id = 's_temp_partition_test'",
                Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}
