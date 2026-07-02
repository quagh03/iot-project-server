package com.huylq.iotprojectserver.alert;

import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

  @Mock private AlertRepository repo;
  @Mock private RegistryService registry;

  private AlertServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AlertServiceImpl(repo, registry);
  }

  @Test
  void raise_resolves_source_device_and_persists_open_alert() {
    Device device = Device.builder().deviceId("s_smoke_1").category(Device.Category.sensor)
        .deviceType("smoke").zone("office_1").status(Device.Status.ACTIVE).build();
    when(registry.find("s_smoke_1")).thenReturn(Optional.of(device));
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Alert result = service.raise("SMOKE", Alert.Severity.CRITICAL, "office_1", "s_smoke_1", "Rule triggered");

    assertThat(result.getStatus()).isEqualTo(Alert.Status.OPEN);
    assertThat(result.getSourceDevice()).isEqualTo(device);
    assertThat(result.getType()).isEqualTo("SMOKE");
    assertThat(result.getSeverity()).isEqualTo(Alert.Severity.CRITICAL);
    verify(repo).save(any());
  }

  @Test
  void raise_tolerates_unresolvable_source_device() {
    when(registry.find("ghost")).thenReturn(Optional.empty());
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Alert result = service.raise("SMOKE", Alert.Severity.CRITICAL, "office_1", "ghost", "Rule triggered");

    assertThat(result.getSourceDevice()).isNull();
    assertThat(result.getStatus()).isEqualTo(Alert.Status.OPEN);
  }

  @Test
  void raise_tolerates_null_source_device_id() {
    when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Alert result = service.raise("OFFLINE", Alert.Severity.WARNING, "office_1", null, "No source");

    assertThat(result.getSourceDevice()).isNull();
    verify(registry, org.mockito.Mockito.never()).find(any());
  }
}
