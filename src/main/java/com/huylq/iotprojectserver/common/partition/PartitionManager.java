package com.huylq.iotprojectserver.common.partition;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
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
 * but disabled by default ({@code iot.partitioning.retention-months=0}). A missing
 * current-month partition (Phase 10: "alerting if a partition is missing") is surfaced as
 * a {@code iot.partition.missing} gauge — a Prometheus/Alertmanager rule on that metric is
 * the intended paging path, not an in-app {@code Alert} row: {@code common} deliberately
 * has no dependency on the {@code alert} domain module (module-boundary invariant —
 * domain modules depend on {@code common}, never the reverse), and a missing partition is
 * an infrastructure failure, not a business/safety event like the {@code alert} module's
 * `SMOKE`/`HEAT` alerts.
 */
@Slf4j
@Component
public class PartitionManager {

  private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyy_MM");
  private static final Pattern PARTITION_NAME = Pattern.compile("^(.+)_(\\d{4})_(\\d{2})$");

  private final JdbcTemplate jdbc;
  private final PartitionConfig config;

  public PartitionManager(JdbcTemplate jdbc, PartitionConfig config, MeterRegistry meterRegistry) {
    this.jdbc = jdbc;
    this.config = config;
    for (String table : config.tables()) {
      Gauge.builder("iot.partition.size.bytes", () -> currentPartitionSizeBytes(table))
          .tag("table", table)
          .description("Byte size of the current month's partition (0 if the partition is missing)")
          .register(meterRegistry);
      Gauge.builder("iot.partition.missing", () -> currentPartitionMissing(table) ? 1 : 0)
          .tag("table", table)
          .description("1 if the current month's partition is missing for this table, else 0")
          .register(meterRegistry);
    }
  }

  @PostConstruct
  public void onStartup() {
    ensureUpcomingPartitions();
    verifyCurrentPartitionsExist();
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
  public void daily() {
    ensureUpcomingPartitions();
    verifyCurrentPartitionsExist();
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
   * Defensive check independent of {@link #ensurePartition} — catches a partition that
   * was expected but is actually absent (manually dropped, a prior DDL failure that was
   * swallowed, etc.), since every write to that month would otherwise fail the moment it
   * arrives rather than being caught proactively. Logs loudly; the {@code
   * iot.partition.missing} gauge is the actual paging signal.
   */
  void verifyCurrentPartitionsExist() {
    for (String table : config.tables()) {
      if (currentPartitionMissing(table)) {
        log.error("Expected partition {}_{} is missing for table {} — writes to the current month will fail",
            table, YearMonth.now().format(SUFFIX), table);
      }
    }
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

  /**
   * {@code pg_total_relation_size} is an O(1) catalog/metadata lookup, not a scan — safe
   * to call on every metrics scrape even for a huge partition. {@code to_regclass}
   * returns {@code NULL} (not an error) for a missing relation.
   */
  private long currentPartitionSizeBytes(String table) {
    String partitionName = table + "_" + YearMonth.now().format(SUFFIX);
    Long size = jdbc.queryForObject(
        "SELECT pg_total_relation_size(to_regclass(?))", Long.class, partitionName);
    return size == null ? 0L : size;
  }

  private boolean currentPartitionMissing(String table) {
    String expected = table + "_" + YearMonth.now().format(SUFFIX);
    return !listPartitions(table).contains(expected);
  }
}
