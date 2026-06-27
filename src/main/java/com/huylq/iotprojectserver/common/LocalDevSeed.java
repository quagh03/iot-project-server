package com.huylq.iotprojectserver.common;

import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.DeviceRepository;
import com.huylq.iotprojectserver.registry.Sensor;
import com.huylq.iotprojectserver.registry.SensorRepository;
import com.huylq.iotprojectserver.security.Role;

import com.huylq.iotprojectserver.security.user.User;
import com.huylq.iotprojectserver.security.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seed data for the {@code local} profile so the schema isn't empty after a fresh boot.
 *
 * <p>Bootstrap admin: {@code admin / changeme} — for local dev only. Change before exposing
 * the instance to anything resembling a network.
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

  private static final String PRESET_PASSWORD = "changeme";

  // Transactional here. All or nothing.
  @Bean
  @Transactional
  ApplicationRunner seedFixtures() {
    log.info("Configuring local dev seed");
    long startMs = System.currentTimeMillis();
    return args -> {
      seedDevices();
      seedUsers();
      log.info("Seeded local dev fixtures in {}ms", System.currentTimeMillis() - startMs);
    };
  }

  void seedDevices() {
    if (deviceRepo.count() > 0) {
      log.debug("Devices already present — skipping device seed");
      return;
    }
    long startMs = System.currentTimeMillis();
    log.info("Seeding local dev devices");

    Device gateway = deviceRepo.save(Device.builder()
        .deviceId("gw_office1_01")
        .category(Device.Category.gateway)
        .deviceType("gateway")
        .zone("office_1")
        .firmwareVersion("1.4.2")
        .status(Device.Status.ACTIVE)
        .protocols(new String[]{"mqtt", "http"})
        .build());

    Device tempSensor = deviceRepo.save(Device.builder()
        .deviceId("s_temp_1")
        .category(Device.Category.sensor)
        .deviceType("temp")
        .zone("office_1")
        .parentGateway(gateway)
        .status(Device.Status.ACTIVE)
        .protocols(new String[]{"mqtt"})
        .build());

    Device smokeSensor = deviceRepo.save(Device.builder()
        .deviceId("s_smoke_1")
        .category(Device.Category.sensor)
        .deviceType("smoke")
        .zone("office_1")
        .parentGateway(gateway)
        .status(Device.Status.ACTIVE)
        .protocols(new String[]{"mqtt"})
        .build());

    deviceRepo.save(Device.builder()
        .deviceId("act_exhaust_1")
        .category(Device.Category.actuator)
        .deviceType("exhst_fan")
        .zone("office_1")
        .status(Device.Status.ACTIVE)
        .protocols(new String[]{"mqtt"})
        .build());

    sensorRepo.save(Sensor.builder()
        .sensorId(tempSensor.getDeviceId())
        .gateway(gateway)
        .type("temp")
        .zone("office_1")
        .build());

    sensorRepo.save(Sensor.builder()
        .sensorId(smokeSensor.getDeviceId())
        .gateway(gateway)
        .type("smoke")
        .zone("office_1")
        .build());

    log.info("Seeded devices in {}ms", System.currentTimeMillis() - startMs);
  }

  void seedUsers() {
    if (userRepo.count() > 0) {
      log.debug("Users already present — skipping user seed");
      return;
    }

    long startMs = System.currentTimeMillis();

    log.info("Seeding local dev users");

    User admin = User.builder()
        .username("admin")
        .passwordHash(passwordEncoder.encode(PRESET_PASSWORD))
        .role(Role.SUPER_ADMIN)
        .status(User.Status.ACTIVE)
        .build();
    userRepo.save(admin);

    User viewer = User.builder()
        .username("user")
        .passwordHash(passwordEncoder.encode(PRESET_PASSWORD))
        .role(Role.VIEWER)
        .status(User.Status.ACTIVE)
        .build();
    userRepo.save(viewer);

    log.info("Seeded users in {}ms", System.currentTimeMillis() - startMs);
  }

}
