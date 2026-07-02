package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleServiceImplTest {

  @Mock private RuleRepository ruleRepo;
  @Mock private AuditService audit;

  private RuleServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new RuleServiceImpl(ruleRepo, audit);
  }

  private static Rule persisted(UUID id, String condition, String action) {
    return Rule.builder().ruleId(id).name("Smoke rule").enabled(true)
        .condition(condition).action(action).priority(0).createdBy("admin-1").build();
  }

  // ---- create -----------------------------------------------------------------------------

  @Test
  void create_rejects_malformed_condition() {
    var cmd = new RuleService.RuleInputCmd("bad", true, "office_1 smoke == true", "alert(SMOKE, CRITICAL)", 0);

    assertThatThrownBy(() -> service.create(cmd, "admin-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(ruleRepo, never()).save(any());
  }

  @Test
  void create_rejects_malformed_action() {
    var cmd = new RuleService.RuleInputCmd("bad", true, "office_1.smoke == true", "exec(rm, -rf)", 0);

    assertThatThrownBy(() -> service.create(cmd, "admin-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(ruleRepo, never()).save(any());
  }

  @Test
  void create_rejects_unknown_alert_severity() {
    var cmd = new RuleService.RuleInputCmd("bad", true, "office_1.smoke == true", "alert(SMOKE, APOCALYPTIC)", 0);

    assertThatThrownBy(() -> service.create(cmd, "admin-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    verify(ruleRepo, never()).save(any());
  }

  @Test
  void create_persists_valid_rule_and_audits() {
    var cmd = new RuleService.RuleInputCmd("Smoke rule", true, "office_1.smoke == true",
        "command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)", 10);
    when(ruleRepo.save(any())).thenAnswer(inv -> {
      Rule r = inv.getArgument(0);
      r.setRuleId(UUID.randomUUID());
      return r;
    });

    Rule result = service.create(cmd, "admin-1", "127.0.0.1");

    assertThat(result.getName()).isEqualTo("Smoke rule");
    assertThat(result.getCreatedBy()).isEqualTo("admin-1");
    assertThat(result.getPriority()).isEqualTo(10);
    verify(audit).user(eq("admin-1"), eq(AuditEvent.RULE_CREATE), anyString(), any(), eq("127.0.0.1"));
  }

  @Test
  void create_defaults_enabled_true_and_priority_zero_when_omitted() {
    var cmd = new RuleService.RuleInputCmd("Rule", null, "office_1.temp > 30", "alert(HEAT, WARNING)", null);
    when(ruleRepo.save(any())).thenAnswer(inv -> {
      Rule r = inv.getArgument(0);
      r.setRuleId(UUID.randomUUID());
      return r;
    });

    Rule result = service.create(cmd, "admin-1", "127.0.0.1");

    assertThat(result.getEnabled()).isTrue();
    assertThat(result.getPriority()).isZero();
  }

  // ---- read / not found ---------------------------------------------------------------------

  @Test
  void get_throws_not_found_for_unknown_rule() {
    UUID id = UUID.randomUUID();
    when(ruleRepo.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(id))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  // ---- replace ----------------------------------------------------------------------------

  @Test
  void replace_rejects_malformed_condition_and_leaves_rule_untouched() {
    UUID id = UUID.randomUUID();
    Rule existing = persisted(id, "office_1.smoke == true", "alert(SMOKE, CRITICAL)");
    when(ruleRepo.findById(id)).thenReturn(Optional.of(existing));

    var cmd = new RuleService.RuleInputCmd("Smoke rule", true, "not valid !!", "alert(SMOKE, CRITICAL)", 0);

    assertThatThrownBy(() -> service.replace(id, cmd, "admin-1", "127.0.0.1"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    assertThat(existing.getCondition()).isEqualTo("office_1.smoke == true");
  }

  @Test
  void replace_updates_fields_and_audits() {
    UUID id = UUID.randomUUID();
    Rule existing = persisted(id, "office_1.smoke == true", "alert(SMOKE, CRITICAL)");
    when(ruleRepo.findById(id)).thenReturn(Optional.of(existing));

    var cmd = new RuleService.RuleInputCmd("Updated name", false, "office_1.temp > 30", "alert(HEAT, WARNING)", 5);
    Rule result = service.replace(id, cmd, "admin-1", "127.0.0.1");

    assertThat(result.getName()).isEqualTo("Updated name");
    assertThat(result.getEnabled()).isFalse();
    assertThat(result.getCondition()).isEqualTo("office_1.temp > 30");
    assertThat(result.getPriority()).isEqualTo(5);
    verify(audit).user(eq("admin-1"), eq(AuditEvent.RULE_UPDATE), eq(id.toString()), any(), eq("127.0.0.1"));
  }

  // ---- patch ------------------------------------------------------------------------------

  @Test
  void patch_toggles_enabled_only_when_provided() {
    UUID id = UUID.randomUUID();
    Rule existing = persisted(id, "office_1.smoke == true", "alert(SMOKE, CRITICAL)");
    existing.setPriority(7);
    when(ruleRepo.findById(id)).thenReturn(Optional.of(existing));

    Rule result = service.patch(id, false, null, "admin-1", "127.0.0.1");

    assertThat(result.getEnabled()).isFalse();
    assertThat(result.getPriority()).isEqualTo(7);
    verify(audit).user(eq("admin-1"), eq(AuditEvent.RULE_PATCH), eq(id.toString()), any(), eq("127.0.0.1"));
  }

  @Test
  void patch_changes_priority_only_when_provided() {
    UUID id = UUID.randomUUID();
    Rule existing = persisted(id, "office_1.smoke == true", "alert(SMOKE, CRITICAL)");
    when(ruleRepo.findById(id)).thenReturn(Optional.of(existing));

    Rule result = service.patch(id, null, 99, "admin-1", "127.0.0.1");

    assertThat(result.getEnabled()).isTrue();
    assertThat(result.getPriority()).isEqualTo(99);
  }

  // ---- delete -----------------------------------------------------------------------------

  @Test
  void delete_removes_rule_and_audits() {
    UUID id = UUID.randomUUID();
    Rule existing = persisted(id, "office_1.smoke == true", "alert(SMOKE, CRITICAL)");
    when(ruleRepo.findById(id)).thenReturn(Optional.of(existing));

    service.delete(id, "admin-1", "127.0.0.1");

    verify(ruleRepo).delete(existing);
    verify(audit).user(eq("admin-1"), eq(AuditEvent.RULE_DELETE), eq(id.toString()), any(), eq("127.0.0.1"));
  }

  // ---- list / count -------------------------------------------------------------------------

  @Test
  void list_filters_by_enabled_when_provided() {
    Page<Rule> page = new PageImpl<>(List.of());
    when(ruleRepo.findByEnabled(eq(true), any(Pageable.class))).thenReturn(page);

    service.list(true, 0, 50);

    verify(ruleRepo).findByEnabled(eq(true), any(Pageable.class));
    verify(ruleRepo, never()).findAll(any(Pageable.class));
  }

  @Test
  void list_returns_all_when_enabled_filter_omitted() {
    Page<Rule> page = new PageImpl<>(List.of());
    when(ruleRepo.findAll(any(Pageable.class))).thenReturn(page);

    service.list(null, 0, 50);

    verify(ruleRepo).findAll(any(Pageable.class));
    verify(ruleRepo, never()).findByEnabled(anyBoolean(), any(Pageable.class));
  }
}
