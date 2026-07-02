package com.huylq.iotprojectserver.api.dto.rule;

/**
 * Narrow patch payload (OpenAPI {@code RulePatch}, API §9) — toggle {@code enabled} /
 * change {@code priority} only. To change {@code condition}/{@code action}, use {@code PUT}.
 */
public record RulePatchRequest(Boolean enabled, Integer priority) {

  public boolean isEmpty() {
    return enabled == null && priority == null;
  }
}
