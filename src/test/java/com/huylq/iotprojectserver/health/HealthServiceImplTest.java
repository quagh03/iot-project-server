package com.huylq.iotprojectserver.health;

import com.huylq.iotprojectserver.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthServiceImplTest {

  private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-25T10:30:00Z");

  @Mock private DeviceHealthRepository repo;

  private HealthServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new HealthServiceImpl(repo);
  }

  @Test
  void upsertHeartbeat_identity_mismatch_is_forbidden() {
    HeartbeatCommand cmd = new HeartbeatCommand("gw_1", "gw_2", (short) 40, (short) 20, (short) -55, NOW);

    assertThatThrownBy(() -> service.upsertHeartbeat(cmd))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

    verify(repo, never()).upsert(anyString(), anyString(), any(OffsetDateTime.class),
        any(), any(), any());
  }

  @Test
  void upsertHeartbeat_happy_path_upserts_online_with_reported_metrics() {
    HeartbeatCommand cmd = new HeartbeatCommand("gw_1", "gw_1", (short) 40, (short) 20, (short) -55, NOW);

    service.upsertHeartbeat(cmd);

    verify(repo).upsert("gw_1", "ONLINE", NOW, (short) 40, (short) 20, (short) -55);
  }

  @Test
  void upsertHeartbeat_mqtt_path_has_no_authenticated_device_id_and_skips_identity_check() {
    HeartbeatCommand cmd = new HeartbeatCommand("gw_1", null, null, null, null, NOW);

    service.upsertHeartbeat(cmd);

    verify(repo).upsert("gw_1", "ONLINE", NOW, null, null, null);
  }

  @Test
  void touchOnline_delegates_to_repo() {
    service.touchOnline("gw_1", NOW);

    verify(repo).touchOnline("gw_1", NOW);
  }

  @Test
  void markOffline_delegates_to_repo() {
    service.markOffline("gw_1");

    verify(repo).markOffline("gw_1");
  }

  @Test
  void getHealth_throws_not_found_when_no_row_exists() {
    when(repo.findById("gw_missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getHealth("gw_missing"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void getHealth_returns_the_row_when_present() {
    DeviceHealth health = DeviceHealth.builder().deviceId("gw_1")
        .connectionStatus(DeviceHealth.ConnectionStatus.ONLINE).lastSeen(NOW).build();
    when(repo.findById("gw_1")).thenReturn(Optional.of(health));

    assertThat(service.getHealth("gw_1")).isSameAs(health);
  }
}
