package com.huylq.iotprojectserver.common;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertRepository;
import com.huylq.iotprojectserver.command.ActuatorStateRepository;
import com.huylq.iotprojectserver.command.Command;
import com.huylq.iotprojectserver.command.CommandRepository;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.health.DeviceHealth;
import com.huylq.iotprojectserver.health.DeviceHealthRepository;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.rules.Rule;
import com.huylq.iotprojectserver.rules.RuleRepository;
import com.huylq.iotprojectserver.security.Role;
import com.huylq.iotprojectserver.security.user.User;
import com.huylq.iotprojectserver.security.user.UserRepository;
import com.huylq.iotprojectserver.telemetry.SensorLatest;
import com.huylq.iotprojectserver.telemetry.SensorLatestRepository;
import com.huylq.iotprojectserver.telemetry.Telemetry;
import com.huylq.iotprojectserver.telemetry.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seed data for the {@code local} profile so the schema isn't empty after a fresh boot.
 * Touches other modules' repositories directly — acceptable only because this is
 * dev-profile fixture loading, never production code paths.
 *
 * <p>Bootstrap users (password {@code changeme} for all — local dev only, change before
 * exposing the instance to anything resembling a network):
 * {@code admin} (SUPER_ADMIN) · {@code manager} (ADMIN) · {@code operator} (OPERATOR)
 * · {@code tech} (TECHNICIAN) · {@code user} (VIEWER).
 *
 * <p>Fixtures cover two live zones ({@code office_1}, {@code meeting}) with gateways,
 * sensors and actuators, devices in every lifecycle state, ~6 h of telemetry history +
 * current state, device health (online and offline), commands in several lifecycle
 * states, a drifted actuator, enabled/disabled rules, and alerts in all three statuses —
 * enough to exercise every read endpoint and the dashboard views without publishing a
 * single MQTT message.
 *
 * <p>Users, devices, and commands are guarded by per-row existence checks (not per-table
 * counts), so the seed CONVERGES on a database that was seeded by an older version of
 * this class — new fixtures are added, existing rows are left alone. High-volume tables
 * (telemetry, alerts) keep a coarse "any rows present" guard.
 */
@Slf4j
@Profile("local")
@Configuration
@RequiredArgsConstructor
public class LocalDevSeed {

  private final DeviceRepository deviceRepo;
  private final SensorRepository sensorRepo;
  private final UserRepository userRepo;
  private final PasswordEncoder passwordEncoder;
  private final TelemetryRepository telemetryRepo;
  private final SensorLatestRepository sensorLatestRepo;
  private final DeviceHealthRepository deviceHealthRepo;
  private final CommandRepository commandRepo;
  private final ActuatorStateRepository actuatorStateRepo;
  private final RuleRepository ruleRepo;
  private final AlertRepository alertRepo;
  private final TransactionTemplate txTemplate;
  private final ObjectMapper json;

  private static final String PRESET_PASSWORD = "changeme";

  // One programmatic transaction around the whole seed — all or nothing, and it lets the
  // @Modifying native upserts (device_health, actuator_state) run from an ApplicationRunner.
  @Bean
  ApplicationRunner seedFixtures() {
    log.info("Configuring local dev seed");
    long startMs = System.currentTimeMillis();
    return args -> txTemplate.executeWithoutResult(tx -> {
      seedUsers();
      seedDevices();
      seedTelemetryAndCurrentState();
      seedDeviceHealth();
      seedRules();
      seedCommandsAndActuatorState();
      seedAlerts();
      log.info("Seeded local dev fixtures in {}ms", System.currentTimeMillis() - startMs);
    });
  }

  void seedUsers() {
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev users (existing usernames untouched)");

    saveUser("admin", Role.SUPER_ADMIN);
    saveUser("manager", Role.ADMIN);
    saveUser("operator", Role.OPERATOR);
    saveUser("tech", Role.TECHNICIAN);
    saveUser("user", Role.VIEWER);

    log.info("Seeded users in {}ms", System.currentTimeMillis() - startMs);
  }

  void seedDevices() {
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev devices (existing ids untouched)");

    // office_1 — the zone the README smoke test targets; keep these ids stable.
    Device gwOffice = saveGateway("gw_office1_01", "office_1", Device.Status.ACTIVE);
    saveSensor("s_temp_1", "temp", "office_1", gwOffice);
    saveSensor("s_hmid_1", "hmid", "office_1", gwOffice);
    saveSensor("s_smoke_1", "smoke", "office_1", gwOffice);
    saveActuator("act_exhaust_1", "exhst_fan", "office_1", Device.Status.ACTIVE);
    saveActuator("act_light_1", "light", "office_1", Device.Status.ACTIVE);

    // meeting — second zone so zone filters return more than one bucket.
    Device gwMeeting = saveGateway("gw_meeting_01", "meeting", Device.Status.ACTIVE);
    saveSensor("s_temp_2", "temp", "meeting", gwMeeting);
    saveSensor("s_hmid_2", "hmid", "meeting", gwMeeting);
    saveSensor("s_smoke_2", "smoke", "meeting", gwMeeting);
    saveActuator("act_ac_1", "ac", "meeting", Device.Status.ACTIVE);
    saveActuator("act_curtain_1", "curtain", "meeting", Device.Status.ACTIVE);

    // Lifecycle variety — registered-but-not-activated, suspended, decommissioned —
    // so status filters, lifecycle transitions, and the 422-on-non-ACTIVE-target
    // command validation all have something to hit.
    saveActuator("act_light_2", "light", "meeting", Device.Status.INACTIVE);
    saveGateway("gw_storage_01", "storage", Device.Status.SUSPENDED);
    saveActuator("act_fan_old", "exhst_fan", "storage", Device.Status.DECOMMISSIONED);

    log.info("Seeded devices in {}ms", System.currentTimeMillis() - startMs);
  }

  /**
   * ~6 h of history at 10-minute intervals for the numeric sensors (sinusoidal so charts
   * look alive), sparse boolean readings for the smoke sensors, and a {@code
   * sensor_latest} row per sensor so {@code /current-state} is populated on first boot.
   */
  void seedTelemetryAndCurrentState() {
    if (telemetryRepo.count() > 0) {
      log.debug("Telemetry already present — skipping telemetry seed");
      return;
    }
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev telemetry history + current state");

    record Series(String sensorId, String type, String zone, String gatewayId,
                  double base, double amplitude, String unit) { }
    List<Series> numeric = List.of(
        new Series("s_temp_1", "temp", "office_1", "gw_office1_01", 23.0, 2.5, "C"),
        new Series("s_hmid_1", "hmid", "office_1", "gw_office1_01", 55.0, 8.0, "%"),
        new Series("s_temp_2", "temp", "meeting", "gw_meeting_01", 25.0, 3.0, "C"),
        new Series("s_hmid_2", "hmid", "meeting", "gw_meeting_01", 60.0, 6.0, "%"));

    OffsetDateTime now = Clocks.nowUtc();
    // PartitionManager only guarantees partitions for the current and next month —
    // clamp history so a boot on the 1st never writes into a missing partition.
    OffsetDateTime monthStart = now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);

    int steps = 36; // 36 × 10 min = 6 h
    List<Telemetry> rows = new ArrayList<>();
    for (int i = steps; i >= 0; i--) {
      OffsetDateTime ts = now.minusMinutes(10L * i);
      if (ts.isBefore(monthStart)) {
        continue;
      }
      double phase = Math.sin(2 * Math.PI * (steps - i) / (double) steps);
      for (Series s : numeric) {
        double value = Math.round((s.base() + s.amplitude() * phase) * 10.0) / 10.0;
        rows.add(Telemetry.builder()
            .ts(ts).zone(s.zone()).gatewayId(s.gatewayId())
            .sensorId(s.sensorId()).sensorType(s.type())
            .valueNum(value).unit(s.unit())
            .build());
      }
      if (i % 3 == 0) { // smoke reported every 30 min, never triggering
        rows.add(Telemetry.builder()
            .ts(ts).zone("office_1").gatewayId("gw_office1_01")
            .sensorId("s_smoke_1").sensorType("smoke").valueBool(false)
            .build());
        rows.add(Telemetry.builder()
            .ts(ts).zone("meeting").gatewayId("gw_meeting_01")
            .sensorId("s_smoke_2").sensorType("smoke").valueBool(false)
            .build());
      }
    }
    telemetryRepo.saveAll(rows);

    if (sensorLatestRepo.count() == 0) {
      for (Series s : numeric) {
        sensorLatestRepo.save(SensorLatest.builder()
            .sensorId(s.sensorId()).zone(s.zone()).sensorType(s.type())
            .valueNum(s.base()).unit(s.unit()).ts(now)
            .build());
      }
      sensorLatestRepo.save(SensorLatest.builder()
          .sensorId("s_smoke_1").zone("office_1").sensorType("smoke").valueBool(false).ts(now)
          .build());
      sensorLatestRepo.save(SensorLatest.builder()
          .sensorId("s_smoke_2").zone("meeting").sensorType("smoke").valueBool(false).ts(now)
          .build());
    }

    log.info("Seeded {} telemetry rows in {}ms", rows.size(), System.currentTimeMillis() - startMs);
  }

  /** Health rows for every MQTT-speaking device — a mix of online and offline. */
  void seedDeviceHealth() {
    if (deviceHealthRepo.count() > 0) {
      log.debug("Device health already present — skipping health seed");
      return;
    }
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev device health");

    OffsetDateTime now = Clocks.nowUtc();
    saveHealth("gw_office1_01", DeviceHealth.ConnectionStatus.ONLINE, now, 41, 12, -58);
    saveHealth("gw_meeting_01", DeviceHealth.ConnectionStatus.ONLINE, now.minusSeconds(25), 47, 18, -63);
    saveHealth("act_exhaust_1", DeviceHealth.ConnectionStatus.ONLINE, now.minusSeconds(40), 22, 5, -61);
    saveHealth("act_ac_1", DeviceHealth.ConnectionStatus.ONLINE, now.minusSeconds(15), 30, 9, -55);
    saveHealth("act_light_1", DeviceHealth.ConnectionStatus.OFFLINE, now.minusHours(2), 25, 4, -74);
    saveHealth("act_curtain_1", DeviceHealth.ConnectionStatus.OFFLINE, now.minusDays(1), 28, 6, -80);
    saveHealth("gw_storage_01", DeviceHealth.ConnectionStatus.OFFLINE, now.minusDays(3), 50, 20, -70);

    log.info("Seeded device health in {}ms", System.currentTimeMillis() - startMs);
  }

  /**
   * Rules written directly against the {@code rules} table, so they must already be in
   * the exact grammar {@code RuleGrammarParser} accepts — the engine parses them on every
   * evaluation. Identifiers only (no hyphens), numeric/ident parameter values.
   */
  void seedRules() {
    if (ruleRepo.count() > 0) {
      log.debug("Rules already present — skipping rule seed");
      return;
    }
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev rules");

    ruleRepo.save(Rule.builder()
        .name("Smoke in office_1 -> exhaust ON + alert")
        .condition("office_1.smoke == true")
        .action("command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)")
        .priority(100)
        .createdBy("seed")
        .build());

    ruleRepo.save(Rule.builder()
        .name("Meeting room too hot -> AC ON")
        .condition("meeting.temp > 28")
        .action("command(act_ac_1, SET, {status: ON, set_temp: 24})")
        .priority(10)
        .createdBy("seed")
        .build());

    ruleRepo.save(Rule.builder()
        .name("Humidity watch (disabled example)")
        .enabled(false)
        .condition("office_1.hmid > 80")
        .action("alert(HUMIDITY_HIGH, WARNING)")
        .priority(1)
        .createdBy("seed")
        .build());

    log.info("Seeded rules in {}ms", System.currentTimeMillis() - startMs);
  }

  /**
   * Commands across the lifecycle plus the {@code actuator_state} mirror the toggle grid
   * reads. {@code act_light_1} is deliberately drifted (desired ON, reported OFF) so
   * {@code GET /actuator-state?drifted=true} returns a row; its PENDING command is aged
   * to TIMEOUT by the sweeper ~30 s after boot — that is the sweeper working, not a bug.
   * {@code act_curtain_1} gets no state row, exercising the 404 path.
   */
  void seedCommandsAndActuatorState() {
    if (commandRepo.existsById("CMD_seed_ac_on")) {
      log.debug("Seed commands already present — skipping command seed");
      return;
    }
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev commands + actuator state");

    String adminId = userRepo.findByUsername("admin")
        .map(u -> u.getId().toString())
        .orElse("seed");
    OffsetDateTime now = Clocks.nowUtc();

    Command acOn = saveCommand("CMD_seed_ac_on", "act_ac_1", "SET",
        Map.of("status", "ON", "set_temp", 24, "mode", "COOL"),
        Command.Status.SUCCESS, adminId, now.minusHours(3));
    acOn.setReceivedAt(now.minusHours(3).plusSeconds(1));
    acOn.setExecutedAt(now.minusHours(3).plusSeconds(2));
    commandRepo.save(acOn);

    Command exhaustOff = saveCommand("CMD_seed_exhaust_off", "act_exhaust_1", "SET",
        Map.of("status", "OFF"),
        Command.Status.SUCCESS, adminId, now.minusHours(6));
    exhaustOff.setReceivedAt(now.minusHours(6).plusSeconds(1));
    exhaustOff.setExecutedAt(now.minusHours(6).plusSeconds(3));
    commandRepo.save(exhaustOff);

    Command exhaustFailed = saveCommand("CMD_seed_exhaust_failed", "act_exhaust_1", "SET",
        Map.of("status", "ON"),
        Command.Status.FAILED, "seed-rule", now.minusDays(1));
    exhaustFailed.setReceivedAt(now.minusDays(1).plusSeconds(1));
    exhaustFailed.setExecutedAt(now.minusDays(1).plusSeconds(2));
    commandRepo.save(exhaustFailed);

    saveCommand("CMD_seed_curtain_lost", "act_curtain_1", "SET",
        Map.of("direction", "DOWN"),
        Command.Status.TIMEOUT, adminId, now.minusHours(1));

    Command lightOn = saveCommand("CMD_seed_light_on", "act_light_1", "SET",
        Map.of("status", "ON", "level", 80),
        Command.Status.PENDING, adminId, now);

    if (actuatorStateRepo.count() == 0) {
      saveActuatorState("act_exhaust_1", "OFF", "OFF", Map.of(), exhaustOff);
      saveActuatorState("act_ac_1", "ON", "ON", Map.of("set_temp", 24, "mode", "COOL"), acOn);
      saveActuatorState("act_light_1", "ON", "OFF", Map.of("level", 80), lightOn); // drifted
      // act_curtain_1 intentionally has no row.
    }

    log.info("Seeded commands + actuator state in {}ms", System.currentTimeMillis() - startMs);
  }

  /** One alert per lifecycle status so list filters and both transitions are testable. */
  void seedAlerts() {
    if (alertRepo.count() > 0) {
      log.debug("Alerts already present — skipping alert seed");
      return;
    }
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev alerts");

    OffsetDateTime now = Clocks.nowUtc();

    alertRepo.save(Alert.builder()
        .type("SMOKE").severity(Alert.Severity.CRITICAL).zone("office_1")
        .sourceDevice(device("s_smoke_1"))
        .message("Smoke detected in office_1 (seed fixture)")
        .build()); // OPEN

    alertRepo.save(Alert.builder()
        .type("TEMP_HIGH").severity(Alert.Severity.WARNING).zone("meeting")
        .sourceDevice(device("s_temp_2"))
        .message("Temperature above 28C in meeting (seed fixture)")
        .status(Alert.Status.ACK)
        .acknowledgedBy("admin").acknowledgedAt(now.minusMinutes(30))
        .build());

    alertRepo.save(Alert.builder()
        .type("DEVICE_OFFLINE").severity(Alert.Severity.INFO).zone("office_1")
        .sourceDevice(device("act_light_1"))
        .message("act_light_1 missed heartbeats (seed fixture)")
        .status(Alert.Status.RESOLVED)
        .resolvedBy("admin").resolvedAt(now.minusHours(1))
        .build());

    log.info("Seeded alerts in {}ms", System.currentTimeMillis() - startMs);
  }

  // ---------------------------------------------------------------------------
  // Helpers

  private void saveUser(String username, Role role) {
    if (userRepo.findByUsername(username).isPresent()) {
      return;
    }
    userRepo.save(User.builder()
        .username(username)
        .passwordHash(passwordEncoder.encode(PRESET_PASSWORD))
        .role(role)
        .status(User.Status.ACTIVE)
        .build());
  }

  private Device saveGateway(String deviceId, String zone, Device.Status status) {
    return deviceRepo.findById(deviceId).orElseGet(() -> deviceRepo.save(Device.builder()
        .deviceId(deviceId)
        .category(Device.Category.gateway)
        .deviceType("gateway")
        .zone(zone)
        .firmwareVersion("1.4.2")
        .status(status)
        .protocols(new String[]{"mqtt", "http"})
        .build()));
  }

  private void saveSensor(String sensorId, String type, String zone, Device gateway) {
    if (!deviceRepo.existsById(sensorId)) {
      deviceRepo.save(Device.builder()
          .deviceId(sensorId)
          .category(Device.Category.sensor)
          .deviceType(type)
          .zone(zone)
          .parentGateway(gateway)
          .status(Device.Status.ACTIVE)
          .protocols(new String[]{"mqtt"})
          .build());
    }
    if (!sensorRepo.existsById(sensorId)) {
      sensorRepo.save(Sensor.builder()
          .sensorId(sensorId)
          .gateway(gateway)
          .type(type)
          .zone(zone)
          .build());
    }
  }

  private void saveActuator(String deviceId, String type, String zone, Device.Status status) {
    if (deviceRepo.existsById(deviceId)) {
      return;
    }
    deviceRepo.save(Device.builder()
        .deviceId(deviceId)
        .category(Device.Category.actuator)
        .deviceType(type)
        .zone(zone)
        .firmwareVersion("1.0.0")
        .status(status)
        .protocols(new String[]{"mqtt"})
        .build());
  }

  private void saveHealth(String deviceId, DeviceHealth.ConnectionStatus status,
                          OffsetDateTime lastSeen, int memPct, int cpuPct, int rssi) {
    // Same native upsert the heartbeat pipeline uses — the entity is @MapsId-backed and
    // never persisted directly, in production code or here.
    deviceHealthRepo.upsert(deviceId, status.name(), lastSeen,
        (short) memPct, (short) cpuPct, (short) rssi);
  }

  private Command saveCommand(String commandId, String targetId, String action,
                              Map<String, Object> parameters, Command.Status status,
                              String issuedBy, OffsetDateTime issuedAt) {
    Device target = device(targetId);
    return commandRepo.save(Command.builder()
        .commandId(commandId)
        .target(target)
        .type(target.getDeviceType())
        .action(action)
        .parameters(parameters)
        .status(status)
        .issuedBy(issuedBy)
        .issuedAt(issuedAt)
        .build());
  }

  private void saveActuatorState(String deviceId, String desired, String reported,
                                 Map<String, Object> attributes, Command lastCommand) {
    // Same pair of native upserts the command pipeline uses on issue + terminal ack.
    actuatorStateRepo.upsertDesired(deviceId, desired, json.writeValueAsString(attributes),
        lastCommand.getCommandId(), lastCommand.getIssuedAt());
    actuatorStateRepo.upsertReported(deviceId, reported);
  }

  private Device device(String deviceId) {
    return deviceRepo.findById(deviceId)
        .orElseThrow(() -> new IllegalStateException("Seed device missing: " + deviceId));
  }
}
