package com.huylq.iotprojectserver.security.detection;

import com.huylq.iotprojectserver.alert.Alert;
import com.huylq.iotprojectserver.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecurityDetectionServiceTest {

  @Mock private AlertService alertService;

  private SecurityDetectionService service;

  @BeforeEach
  void setUp() {
    service = new SecurityDetectionService(alertService,
        new DetectionProperties(3, 3, 3, 2));
  }

  @Test
  void auth_failure_burst_alerts_exactly_once_at_the_threshold() {
    service.recordAuthFailure("alice", "10.0.0.1");
    service.recordAuthFailure("alice", "10.0.0.1");
    verify(alertService, times(0)).raise(anyString(), any(), any(), any(), anyString());

    service.recordAuthFailure("alice", "10.0.0.1"); // 3rd call crosses threshold=3

    verify(alertService, times(1)).raise(eq("AUTH_FAILURE_BURST"), eq(Alert.Severity.WARNING),
        any(), any(), anyString());
  }

  @Test
  void auth_failure_burst_does_not_re_alert_on_further_failures_in_the_same_window() {
    for (int i = 0; i < 6; i++) {
      service.recordAuthFailure("alice", "10.0.0.1");
    }

    verify(alertService, times(1)).raise(eq("AUTH_FAILURE_BURST"), any(), any(), any(), anyString());
  }

  @Test
  void different_usernames_have_independent_counters() {
    service.recordAuthFailure("alice", "10.0.0.1");
    service.recordAuthFailure("alice", "10.0.0.1");
    service.recordAuthFailure("bob", "10.0.0.2");
    service.recordAuthFailure("bob", "10.0.0.2");

    verify(alertService, times(0)).raise(anyString(), any(), any(), any(), anyString());
  }

  @Test
  void refresh_reuse_alerts_immediately_without_needing_a_burst() {
    service.recordRefreshReuse("user-123");

    verify(alertService).raise(eq("TOKEN_REUSE_DETECTED"), eq(Alert.Severity.CRITICAL),
        any(), eq("user-123"), anyString());
  }

  @Test
  void rate_limit_spike_alerts_at_threshold() {
    service.recordRateLimitDenial("TELEMETRY", "gw_1");
    service.recordRateLimitDenial("TELEMETRY", "gw_1");
    service.recordRateLimitDenial("TELEMETRY", "gw_1");

    verify(alertService).raise(eq("RATE_LIMIT_SPIKE"), eq(Alert.Severity.WARNING), any(), any(), anyString());
  }

  @Test
  void forbidden_spike_alerts_at_threshold() {
    for (int i = 0; i < 3; i++) {
      service.recordAccessDenied("203.0.113.5", "/api/v1/devices");
    }

    verify(alertService).raise(eq("FORBIDDEN_SPIKE"), eq(Alert.Severity.WARNING), any(), any(), anyString());
  }

  @Test
  void command_timeout_burst_alerts_at_threshold() {
    service.recordCommandTimeout("act_exhaust_1");
    verify(alertService, times(0)).raise(anyString(), any(), any(), any(), anyString());

    service.recordCommandTimeout("act_exhaust_1"); // 2nd call crosses threshold=2

    verify(alertService).raise(eq("COMMAND_SUPPRESSION_SUSPECTED"), eq(Alert.Severity.CRITICAL),
        any(), eq("act_exhaust_1"), anyString());
  }
}
