package com.huylq.iotprojectserver.alert;

import java.util.Collection;

/**
 * Narrow published interface (System Design §9 module-boundary rule: cross-module reads
 * go through an interface, never a repository) for modules that only need to ask "is
 * there an unresolved hazard here" — today, {@code command}'s safety interlock — without
 * depending on the full {@link AlertService} (raise/list/acknowledge/resolve).
 */
public interface OpenAlertQuery {

  /**
   * True if an {@code OPEN} alert of any of the given {@code types} exists for {@code zone}.
   */
  boolean existsOpenAlert(String zone, Collection<String> types);
}
