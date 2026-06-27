package com.huylq.iotprojectserver.security;

/**
 * Single source of truth for user authority levels.
 *
 * <p><b>Declaration order is the privilege ladder, highest first.</b> Every role
 * implies the one declared after it — {@link #SUPER_ADMIN} satisfies any
 * {@code hasRole('…')} check, {@link #VIEWER} satisfies only itself.
 *
 * <p>The {@code RoleHierarchy} bean in {@code SecurityConfig} is built from
 * {@link #values()} in this order, the JWT {@code role} claim carries
 * {@link #name()} verbatim, and the {@code User.role} JPA column stores the
 * same string.
 *
 * <p><b>To add or remove a role:</b>
 * <ol>
 *   <li>Insert / remove the constant here at the correct privilege position.</li>
 *   <li>Ship a Flyway migration that updates the {@code users.role} CHECK
 *       constraint to match.</li>
 *   <li>Audit existing {@code @PreAuthorize("hasRole('…')")} sites for any
 *       text-level drift — these aren't compile-checked against the enum.</li>
 * </ol>
 */
public enum Role {
  SUPER_ADMIN,
  ADMIN,
  OPERATOR,
  TECHNICIAN,
  VIEWER;

  /**
   * Spring authority name, e.g. {@code "ROLE_ADMIN"}.
   */
  public String authority() {
    return "ROLE_" + name();
  }
}
