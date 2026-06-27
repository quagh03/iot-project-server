package com.huylq.iotprojectserver.common.partition;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Partition creation + retention policy for monthly range-partitioned tables.
 *
 * @param tables          parent tables to manage (default: {@code telemetry}, {@code audit_logs})
 * @param retentionMonths drop partitions older than this many months; {@code 0} disables drops.
 *                        Defaults to 0 per Open Question #1 (retention horizon TBD).
 * @param dryRun          when true, retention only logs what it would drop. Defaults true for safety.
 */
@ConfigurationProperties("iot.partitioning")
public record PartitionConfig(List<String> tables, int retentionMonths, boolean dryRun) {

  public PartitionConfig {
    if (tables == null || tables.isEmpty()) {
      tables = List.of("telemetry", "audit_logs");
    }
  }
}
