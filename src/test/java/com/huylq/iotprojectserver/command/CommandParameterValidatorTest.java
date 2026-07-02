package com.huylq.iotprojectserver.command;

import com.huylq.iotprojectserver.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandParameterValidatorTest {

  @Test
  void unsupported_action_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("light", "TOGGLE", Map.of("status", "ON")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void unknown_device_type_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("smoke_alarm", "SET", Map.of("status", "ON")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void light_requires_status() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("light", "SET", Map.of("level", 50)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void light_status_on_with_level_is_valid() {
    var v = CommandParameterValidator.validate("light", "SET", Map.of("status", "ON", "level", 50));
    assertThat(v.desiredState()).isEqualTo("ON");
    assertThat(v.attributes()).containsEntry("level", 50);
  }

  @Test
  void light_level_out_of_range_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("light", "SET", Map.of("status", "ON", "level", 200)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void light_unknown_parameter_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("light", "SET",
        Map.of("status", "ON", "flux_capacitor", true)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void ac_full_parameters_are_valid() {
    var v = CommandParameterValidator.validate("ac", "SET",
        Map.of("status", "ON", "set_temp", 24, "mode", "COOL"));
    assertThat(v.desiredState()).isEqualTo("ON");
    assertThat(v.attributes()).containsEntry("set_temp", 24).containsEntry("mode", "COOL");
  }

  @Test
  void ac_set_temp_out_of_bounds_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("ac", "SET",
        Map.of("status", "ON", "set_temp", 40)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void ac_invalid_mode_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("ac", "SET",
        Map.of("status", "ON", "mode", "TURBO")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void exhaust_fan_status_only_is_valid() {
    var v = CommandParameterValidator.validate("exhst_fan", "SET", Map.of("status", "ON"));
    assertThat(v.desiredState()).isEqualTo("ON");
    assertThat(v.attributes()).isEmpty();
  }

  @Test
  void exhaust_fan_rejects_extra_parameters() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("exhst_fan", "SET",
        Map.of("status", "ON", "level", 3)))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void curtain_direction_is_valid() {
    var v = CommandParameterValidator.validate("curtain", "SET", Map.of("direction", "DOWN"));
    assertThat(v.desiredState()).isEqualTo("DOWN");
  }

  @Test
  void curtain_invalid_direction_is_unprocessable() {
    assertThatThrownBy(() -> CommandParameterValidator.validate("curtain", "SET", Map.of("direction", "OPEN")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }
}
