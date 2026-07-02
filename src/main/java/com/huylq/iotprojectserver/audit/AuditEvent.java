package com.huylq.iotprojectserver.audit;

/**
 * Catalog of audit-log event codes — the single source of truth for what gets written
 * into {@code audit_logs.event}.
 *
 * <p>The enum {@link #name() name} is the Java identifier (uppercase, underscores);
 * the {@link #code()} is the stable wire/storage string. The two intentionally differ
 * so queries / dashboards can use dotted/kebab forms while code keeps a typed constant.
 *
 * <p>Add a new event by inserting a constant here — callers can only pass values
 * declared on this enum, so a typo becomes a compile error.
 */
public enum AuditEvent {

  // ---- security/user -----------------------------------------------------------------
  USER_LOGIN("user.login"),
  USER_LOGIN_FAILED("user.login.failed"),
  USER_LOGOUT("user.logout"),
  USER_TOKEN_ROTATED("user.token.rotated"),
  USER_TOKEN_REUSE_DETECTED("user.token.reuse-detected"),

  USER_CREATE("user.create"),
  USER_UPDATE("user.update"),
  USER_DELETE("user.delete"),
  USER_PASSWORD_RESET("user.password-reset"),

  // ---- registry / device lifecycle (Phase 3) ----------------------------------------
  DEVICE_REGISTER("device.register"),
  DEVICE_UPDATE("device.update"),
  DEVICE_ACTIVATE("device.activate"),
  DEVICE_SUSPEND("device.suspend"),
  DEVICE_DECOMMISSION("device.decommission"),
  DEVICE_CREDENTIAL_ISSUE("device.credential.issue"),
  DEVICE_CREDENTIAL_ROTATE("device.credential.rotate"),
  DEVICE_SCOPES_REPLACE("device.scopes.replace"),

  // ---- command / operator control plane (Phase 6) ------------------------------------
  COMMAND_ISSUE("command.issue"),
  COMMAND_EXECUTE("command.execute"),
  COMMAND_TIMEOUT("command.timeout"),
  MANUAL_COMMAND("command.manual"),
  SAFETY_OVERRIDE("command.safety-override"),

  // ---- rules (Phase 7) ----------------------------------------------------------------
  RULE_CREATE("rule.create"),
  RULE_UPDATE("rule.update"),
  RULE_PATCH("rule.patch"),
  RULE_DELETE("rule.delete"),

  // ---- alerts (Phase 8) ---------------------------------------------------------------
  ALERT_ACKNOWLEDGE("alert.acknowledge"),
  ALERT_RESOLVE("alert.resolve"),

  // ---- system (PartitionManager, retention sweeper, scheduled jobs) ------------------
  PARTITION_CREATED("partition.created"),
  PARTITION_DROPPED("partition.dropped");

  private final String code;

  AuditEvent(String code) {
    this.code = code;
  }

  /**
   * Stable string written to {@code audit_logs.event}.
   */
  public String code() {
    return code;
  }
}
