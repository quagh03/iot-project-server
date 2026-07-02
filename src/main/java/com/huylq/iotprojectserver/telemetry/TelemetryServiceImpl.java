package com.huylq.iotprojectserver.telemetry;

import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.health.HealthService;
import com.huylq.iotprojectserver.registry.RegistryService;
import com.huylq.iotprojectserver.registry.Sensor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
class TelemetryServiceImpl implements TelemetryService {

  private final TelemetryRepository telemetryRepo;
  private final SensorLatestRepository sensorLatestRepo;
  private final RegistryService registry;
  private final RuleEventPublisher ruleEvents;
  private final TelemetryIngestProperties props;
  private final HealthService healthService;

  @Override
  @Transactional
  public void ingest(TelemetryIngestCommand command) {
    if (command.readings() == null || command.readings().isEmpty()) {
      throw ApiException.unprocessable("readings must not be empty");
    }
    if (command.authenticatedDeviceId() != null
        && !command.authenticatedDeviceId().equals(command.gatewayId())) {
      log.warn("Telemetry identity mismatch: token={} payload.gatewayId={}",
          command.authenticatedDeviceId(), command.gatewayId());
      throw ApiException.forbidden("gatewayId does not match the authenticated device identity");
    }

    List<Telemetry> rows = new ArrayList<>(command.readings().size());
    for (ReadingCommand r : command.readings()) {
      validateReading(command.gatewayId(), r);
      flagSkewIfImplausible(r);
      rows.add(Telemetry.builder()
          .ts(r.ts())
          .zone(command.zone())
          .gatewayId(command.gatewayId())
          .sensorId(r.sensorId())
          .sensorType(r.sensorType())
          .valueNum(r.valueNum())
          .valueBool(r.valueBool())
          .unit(r.unit())
          .build());
    }
    telemetryRepo.saveAll(rows);
    // A telemetry reading is itself liveness evidence (System Design §6/§8: "heartbeat/
    // telemetry flip it ONLINE") — leaves resource-metric columns to the heartbeat path.
    healthService.touchOnline(command.gatewayId(), command.receivedAt());

    for (ReadingCommand r : command.readings()) {
      sensorLatestRepo.upsert(r.sensorId(), command.zone(), r.sensorType(), r.valueNum(), r.valueBool(),
          r.unit(), r.ts());
      ruleEvents.publish(new ReadingEvent(r.sensorId(), r.sensorType(), r.valueNum(), r.valueBool(),
          command.zone(), r.ts()));
    }
    log.debug("Ingested {} reading(s) for gateway {} zone {}",
        rows.size(), command.gatewayId(), command.zone());
  }

  @Override
  @Transactional(readOnly = true)
  public TelemetryPage queryHistory(String sensorId, String zone, OffsetDateTime from, OffsetDateTime to,
                                    String cursor, int pageSize) {
    TelemetryCursor c = cursor != null ? TelemetryCursor.decode(cursor) : new TelemetryCursor(to, Long.MAX_VALUE);
    Pageable pageable = PageRequest.of(0, pageSize + 1);
    List<Telemetry> rows = sensorId != null
        ? telemetryRepo.findBySensorPage(sensorId, from, to, c.ts(), c.id(), pageable)
        : telemetryRepo.findByZonePage(zone, from, to, c.ts(), c.id(), pageable);

    boolean hasMore = rows.size() > pageSize;
    List<Telemetry> page = hasMore ? rows.subList(0, pageSize) : rows;
    String nextCursor = null;
    if (hasMore) {
      Telemetry last = page.get(page.size() - 1);
      nextCursor = new TelemetryCursor(last.getTs(), last.getId()).encode();
    }
    return new TelemetryPage(page, nextCursor, hasMore);
  }

  @Override
  @Transactional(readOnly = true)
  public List<SensorLatest> currentState(String zone) {
    return zone != null ? sensorLatestRepo.findByZone(zone) : sensorLatestRepo.findAll();
  }

  @Override
  @Transactional(readOnly = true)
  public SensorLatest latest(String sensorId) {
    return sensorLatestRepo.findById(sensorId)
        .orElseThrow(() -> ApiException.notFound("No current reading for sensor " + sensorId));
  }

  /**
   * Registry-derived whitelist (System Design §7): an unknown {@code sensorId}, a
   * {@code sensorType} that doesn't match what the sensor was registered as, or a
   * {@code sensorId} registered under a different gateway are all rejected. For MQTT —
   * which has no broker-asserted identity yet (§7, broker ACLs land in Phase 10) — the
   * gateway cross-check here is the "never trust the transport alone" control; for HTTP
   * it's redundant with the JWT-identity check above, which is fine.
   */
  private void validateReading(String gatewayId, ReadingCommand r) {
    if ((r.valueNum() != null) == (r.valueBool() != null)) {
      throw ApiException.unprocessable(
          "Exactly one of valueNum/valueBool is required for sensor " + r.sensorId());
    }
    Sensor sensor = registry.findSensor(r.sensorId())
        .orElseThrow(() -> ApiException.unprocessable("Unknown sensorId: " + r.sensorId()));
    if (!sensor.getType().equals(r.sensorType())) {
      throw ApiException.unprocessable("sensorType mismatch for sensor " + r.sensorId());
    }
    if (sensor.getGateway() == null || !sensor.getGateway().getDeviceId().equals(gatewayId)) {
      throw ApiException.unprocessable(
          "sensorId " + r.sensorId() + " is not registered under gateway " + gatewayId);
    }
  }

  /**
   * Stale-replay defense (System Design §7): flags, never blocks — a Phase 10 detection
   * signal, not a validation gate.
   */
  private void flagSkewIfImplausible(ReadingCommand r) {
    OffsetDateTime now = Clocks.nowUtc();
    Duration skew = Duration.between(r.ts(), now); // positive when the device ts is in the past
    if (skew.isNegative() && skew.negated().compareTo(props.maxClockSkewFuture()) > 0) {
      log.warn("Implausible future ts for sensor {}: deviceTs={} serverTs={} skewAhead={}",
          r.sensorId(), r.ts(), now, skew.negated());
    } else if (!skew.isNegative() && skew.compareTo(props.maxClockSkewPast()) > 0) {
      log.warn("Implausible past ts (possible stale-replay) for sensor {}: deviceTs={} serverTs={} skewBehind={}",
          r.sensorId(), r.ts(), now, skew);
    }
  }
}
