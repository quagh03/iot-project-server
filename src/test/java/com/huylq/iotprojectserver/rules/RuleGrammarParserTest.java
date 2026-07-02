package com.huylq.iotprojectserver.rules;

import com.huylq.iotprojectserver.common.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleGrammarParserTest {

  // ---- condition: allow ------------------------------------------------------------------

  @Test
  void parses_boolean_equality_condition() {
    RuleCondition c = RuleGrammarParser.parseCondition("office_1.smoke == true");

    assertThat(c.combinator()).isEqualTo(RuleCondition.Combinator.SINGLE);
    assertThat(c.clauses()).hasSize(1);
    RuleCondition.Clause clause = c.clauses().get(0);
    assertThat(clause.zone()).isEqualTo("office_1");
    assertThat(clause.sensorType()).isEqualTo("smoke");
    assertThat(clause.operator()).isEqualTo(RuleCondition.Operator.EQ);
    assertThat(clause.literal()).isEqualTo(true);
  }

  @Test
  void parses_numeric_threshold_condition() {
    RuleCondition c = RuleGrammarParser.parseCondition("office_1.temp > 30");

    RuleCondition.Clause clause = c.clauses().get(0);
    assertThat(clause.operator()).isEqualTo(RuleCondition.Operator.GT);
    assertThat(clause.literal()).isEqualTo(30.0);
  }

  @Test
  void parses_negative_and_decimal_numbers() {
    RuleCondition c = RuleGrammarParser.parseCondition("office_1.temp <= -3.5");

    assertThat(c.clauses().get(0).literal()).isEqualTo(-3.5);
  }

  @Test
  void parses_all_operators() {
    for (String op : new String[]{"==", "!=", ">", "<", ">=", "<="}) {
      RuleCondition c = RuleGrammarParser.parseCondition("office_1.temp " + op + " 10");
      assertThat(c.clauses().get(0).operator()).isNotNull();
    }
  }

  @Test
  void parses_and_combined_clauses() {
    RuleCondition c = RuleGrammarParser.parseCondition("office_1.temp > 30 && office_1.hmid > 70");

    assertThat(c.combinator()).isEqualTo(RuleCondition.Combinator.AND);
    assertThat(c.clauses()).hasSize(2);
  }

  @Test
  void parses_or_combined_clauses() {
    RuleCondition c = RuleGrammarParser.parseCondition("office_1.smoke == true || office_2.smoke == true");

    assertThat(c.combinator()).isEqualTo(RuleCondition.Combinator.OR);
    assertThat(c.clauses()).hasSize(2);
  }

  // ---- condition: deny --------------------------------------------------------------------

  @Test
  void rejects_mixed_and_or_combinators() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition(
        "office_1.temp > 30 && office_1.hmid > 70 || office_2.smoke == true"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_boolean_literal_with_ordering_operator() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("office_1.smoke > true"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_missing_dot() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("office_1 smoke == true"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_string_literal() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("office_1.smoke == \"true\""))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_blank_condition() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("   "))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_trailing_garbage() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("office_1.temp > 30 extra"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_attempted_method_call_syntax() {
    // A classic SpEL/expression-language RCE shape: T(Type).staticMethod(...). The
    // grammar has no notion of method calls or type references at all — IDENT '(' isn't
    // valid clause syntax (a clause is IDENT '.' IDENT op literal), so this fails to parse
    // structurally rather than needing a blocklist.
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition(
        "T(java.lang.Runtime).getRuntime().exec('rm -rf /') == true"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_disallowed_characters() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("office_1.smoke == true; rm -rf /"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_dollar_and_special_characters() {
    assertThatThrownBy(() -> RuleGrammarParser.parseCondition("office_1.smoke == ${java.version}"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  // ---- action: allow ----------------------------------------------------------------------

  @Test
  void parses_command_and_alert_effects() {
    RuleAction a = RuleGrammarParser.parseAction(
        "command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)");

    assertThat(a.effects()).hasSize(2);
    var cmd = (RuleAction.CommandEffect) a.effects().get(0);
    assertThat(cmd.targetId()).isEqualTo("act_exhaust_1");
    assertThat(cmd.action()).isEqualTo("SET");
    assertThat(cmd.parameters()).containsEntry("status", "ON");
    var alert = (RuleAction.AlertEffect) a.effects().get(1);
    assertThat(alert.type()).isEqualTo("SMOKE");
    assertThat(alert.severity()).isEqualTo("CRITICAL");
  }

  @Test
  void parses_command_with_multiple_and_numeric_parameters() {
    RuleAction a = RuleGrammarParser.parseAction("command(AC_01, SET, {status: ON, set_temp: 24, mode: COOL})");

    var cmd = (RuleAction.CommandEffect) a.effects().get(0);
    assertThat(cmd.parameters()).containsEntry("status", "ON")
        .containsEntry("set_temp", 24.0)
        .containsEntry("mode", "COOL");
  }

  @Test
  void parses_command_with_empty_parameters() {
    RuleAction a = RuleGrammarParser.parseAction("command(light_1, SET, {})");

    var cmd = (RuleAction.CommandEffect) a.effects().get(0);
    assertThat(cmd.parameters()).isEmpty();
  }

  @Test
  void parses_single_alert_only_action() {
    RuleAction a = RuleGrammarParser.parseAction("alert(OFFLINE, WARNING)");

    assertThat(a.effects()).hasSize(1);
  }

  // ---- action: deny -----------------------------------------------------------------------

  @Test
  void rejects_unknown_effect_name() {
    assertThatThrownBy(() -> RuleGrammarParser.parseAction("exec(rm, -rf, {})"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_duplicate_parameter_keys() {
    assertThatThrownBy(() -> RuleGrammarParser.parseAction("command(light_1, SET, {status: ON, status: OFF})"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_malformed_object_missing_closing_brace() {
    assertThatThrownBy(() -> RuleGrammarParser.parseAction("command(light_1, SET, {status: ON)"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_missing_semicolon_between_effects() {
    assertThatThrownBy(() -> RuleGrammarParser.parseAction("alert(SMOKE, CRITICAL) alert(SMOKE, CRITICAL)"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_alert_with_wrong_arg_count() {
    assertThatThrownBy(() -> RuleGrammarParser.parseAction("alert(SMOKE)"))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }

  @Test
  void rejects_blank_action() {
    assertThatThrownBy(() -> RuleGrammarParser.parseAction(""))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
  }
}
