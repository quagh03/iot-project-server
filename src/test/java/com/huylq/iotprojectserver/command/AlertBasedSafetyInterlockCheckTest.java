package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.alert.OpenAlertQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertBasedSafetyInterlockCheckTest {

  @Mock private OpenAlertQuery openAlerts;

  private AlertBasedSafetyInterlockCheck check;

  @BeforeEach
  void setUp() {
    SafetyInterlockProperties props = new SafetyInterlockProperties(Map.of("exhst_fan", List.of("SMOKE")));
    check = new AlertBasedSafetyInterlockCheck(openAlerts, props);
  }

  @Test
  void de_escalating_a_safety_actuator_with_an_open_matching_alert_is_held() {
    when(openAlerts.existsOpenAlert(eq("office_1"), eq(List.of("SMOKE")))).thenReturn(true);

    assertThat(check.violatesActiveSafety("exhst_1", "office_1", "exhst_fan", "OFF")).isTrue();
  }

  @Test
  void escalating_a_safety_actuator_is_never_held_even_with_an_open_alert() {
    assertThat(check.violatesActiveSafety("exhst_1", "office_1", "exhst_fan", "ON")).isFalse();
    verify(openAlerts, never()).existsOpenAlert(any(), any());
  }

  @Test
  void de_escalating_with_no_open_alert_is_not_held() {
    when(openAlerts.existsOpenAlert(eq("office_1"), eq(List.of("SMOKE")))).thenReturn(false);

    assertThat(check.violatesActiveSafety("exhst_1", "office_1", "exhst_fan", "OFF")).isFalse();
  }

  @Test
  void a_device_type_not_classified_as_safety_is_never_held() {
    assertThat(check.violatesActiveSafety("light_1", "office_1", "light", "OFF")).isFalse();
    verify(openAlerts, never()).existsOpenAlert(any(), any());
  }

  @Test
  void stop_and_closed_also_count_as_de_escalating() {
    when(openAlerts.existsOpenAlert(eq("office_1"), eq(List.of("SMOKE")))).thenReturn(true);

    assertThat(check.violatesActiveSafety("exhst_1", "office_1", "exhst_fan", "STOP")).isTrue();
    assertThat(check.violatesActiveSafety("exhst_1", "office_1", "exhst_fan", "closed")).isTrue();
  }
}
