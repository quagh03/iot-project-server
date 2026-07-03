package com.huylq.iotprojectserver.alert;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.registry.Device;
import com.huylq.iotprojectserver.registry.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertServiceImplTest {

  @Mock private AlertRepository repo;
  @Mock private RegistryService registry;
  @Mock private AuditService audit;

  private AlertServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new AlertServiceImpl(repo, registry, audit);
  }

  private static Alert persisted(Long id, Alert.Status status) {
    return Alert.builder().id(id).type("SMOKE").severity(Alert.Severity.CRITICAL)
        .zone("office_1").status(status).build();
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

  // ---- get --------------------------------------------------------------------------------

  @Test
  void get_throws_not_found_for_unknown_alert() {
    when(repo.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(99L))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  // ---- acknowledge --------------------------------------------------------------------------

  @Test
  void acknowledge_open_alert_transitions_to_ack_and_audits() {
    Alert alert = persisted(1L, Alert.Status.OPEN);
    when(repo.findById(1L)).thenReturn(Optional.of(alert));

    Alert result = service.acknowledge(1L, "operator-1", "127.0.0.1");

    assertThat(result.getStatus()).isEqualTo(Alert.Status.ACK);
    assertThat(result.getAcknowledgedBy()).isEqualTo("operator-1");
    assertThat(result.getAcknowledgedAt()).isNotNull();
    verify(audit).user(eq("operator-1"), eq(AuditEvent.ALERT_ACKNOWLEDGE), eq("1"), any(), eq("127.0.0.1"));
  }

  @Test
  void acknowledge_already_acked_alert_is_invalid_transition() {
    Alert alert = persisted(1L, Alert.Status.ACK);
    when(repo.findById(1L)).thenReturn(Optional.of(alert));

    assertThatThrownBy(() -> service.acknowledge(1L, "operator-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void acknowledge_resolved_alert_is_invalid_transition() {
    Alert alert = persisted(1L, Alert.Status.RESOLVED);
    when(repo.findById(1L)).thenReturn(Optional.of(alert));

    assertThatThrownBy(() -> service.acknowledge(1L, "operator-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
  }

  // ---- resolve ----------------------------------------------------------------------------

  @Test
  void resolve_open_alert_transitions_to_resolved_and_audits() {
    Alert alert = persisted(1L, Alert.Status.OPEN);
    when(repo.findById(1L)).thenReturn(Optional.of(alert));

    Alert result = service.resolve(1L, "operator-1", "127.0.0.1");

    assertThat(result.getStatus()).isEqualTo(Alert.Status.RESOLVED);
    assertThat(result.getResolvedBy()).isEqualTo("operator-1");
    verify(audit).user(eq("operator-1"), eq(AuditEvent.ALERT_RESOLVE), eq("1"), any(), eq("127.0.0.1"));
  }

  @Test
  void resolve_acked_alert_transitions_to_resolved() {
    Alert alert = persisted(1L, Alert.Status.ACK);
    when(repo.findById(1L)).thenReturn(Optional.of(alert));

    Alert result = service.resolve(1L, "operator-1", "127.0.0.1");

    assertThat(result.getStatus()).isEqualTo(Alert.Status.RESOLVED);
  }

  @Test
  void resolve_already_resolved_alert_is_invalid_transition() {
    Alert alert = persisted(1L, Alert.Status.RESOLVED);
    when(repo.findById(1L)).thenReturn(Optional.of(alert));

    assertThatThrownBy(() -> service.resolve(1L, "operator-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
  }

  // ---- list -------------------------------------------------------------------------------

  @Test
  void list_delegates_to_repository_with_filters() {
    Page<Alert> page = new PageImpl<>(List.of(persisted(1L, Alert.Status.OPEN)));
    when(repo.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
        .thenReturn(page);

    AlertPage result = service.list(Alert.Status.OPEN, "office_1", Alert.Severity.CRITICAL, null, null, null, 50);

    assertThat(result.items()).hasSize(1);
    assertThat(result.hasMore()).isFalse();
  }

  // ---- existsOpenAlert (OpenAlertQuery, consumed by command's safety interlock) -----------

  @Test
  void exists_open_alert_delegates_to_repository() {
    when(repo.existsByZoneAndTypeInAndStatus("office_1", List.of("SMOKE"), Alert.Status.OPEN)).thenReturn(true);

    assertThat(service.existsOpenAlert("office_1", List.of("SMOKE"))).isTrue();
  }

  @Test
  void exists_open_alert_short_circuits_on_empty_type_list() {
    assertThat(service.existsOpenAlert("office_1", List.of())).isFalse();
    verify(repo, org.mockito.Mockito.never()).existsByZoneAndTypeInAndStatus(any(), any(), any());
  }
}
