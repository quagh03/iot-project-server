package com.huylq.iotprojectserver.common.partition;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates monthly range partitions for partitioned tables and (optionally) drops old ones.
 *
 * <p>Naming convention: {@code {table}_{yyyy}_{MM}} — matches the seed partitions in
 * {@code V1__init_schema.sql}.
 *
 * <p>Partition creation runs at startup and daily; drop-don't-delete retention is wired
 * but disabled by default ({@code iot.partitioning.retention-months=0}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartitionManager {

  private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
  private static final Pattern PARTITION_NAME = Pattern.compile("^(.+)_(\\d{4})_(\\d{2})$");

  private final JdbcTemplate jdbc;
  private final PartitionConfig config;

  @PostConstruct
  public void onStartup() {
    ensureUpcomingPartitions();
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  public void daily() {
    ensureUpcomingPartitions();
    if (config.retentionMonths() > 0) dropExpiredPartitions();
  }

  /**
   * Ensure the current and next month's partitions exist for every managed table.
   */
  @Transactional
  public void ensureUpcomingPartitions() {
    YearMonth current = YearMonth.now();
    YearMonth next = current.plusMonths(1);
    for (String table : config.tables()) {
      ensurePartition(table, current);
      ensurePartition(table, next);
    }
  }

  void ensurePartition(String table, YearMonth month) {
    String childName = table + "_" + month.format(SUFFIX);
    String fromBound = month.atDay(1) + " 00:00:00+00";
    String toBound = month.plusMonths(1).atDay(1) + " 00:00:00+00";

    String ddl = ("""
        CREATE TABLE IF NOT EXISTS %s PARTITION OF %s
        FOR VALUES FROM ('%s') TO ('%s')
        """).formatted(childName, table, fromBound, toBound);
    jdbc.execute(ddl);
    log.debug("Ensured partition {} on {}", childName, table);
  }

  /**
   * Drop partitions whose month is older than {@code retentionMonths}. When
   * {@code dryRun=true} (the default), only logs the drop set.
   */
  @Transactional
  public void dropExpiredPartitions() {
    YearMonth cutoff = YearMonth.from(LocalDate.now()).minusMonths(config.retentionMonths());
    for (String table : config.tables()) {
      List<String> children = listPartitions(table);
      for (String child : children) {
        Matcher m = PARTITION_NAME.matcher(child);
        if (!m.matches()) continue;
        YearMonth childMonth = YearMonth.of(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        if (childMonth.isBefore(cutoff)) {
          if (config.dryRun()) {
            log.info("[dry-run] Would DROP TABLE {} (month {} < cutoff {})", child, childMonth, cutoff);
          } else {
            log.info("Dropping partition {} (month {} < cutoff {})", child, childMonth, cutoff);
            jdbc.execute("DROP TABLE IF EXISTS " + child);
          }
        }
      }
    }
  }

  List<String> listPartitions(String parentTable) {
    return jdbc.queryForList("""
        SELECT child.relname
        FROM pg_inherits inh
        JOIN pg_class child  ON inh.inhrelid  = child.oid
        JOIN pg_class parent ON inh.inhparent = parent.oid
        WHERE parent.relname = ?
        """, String.class, parentTable);
  }
}
