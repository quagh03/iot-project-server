package com.huylq.iotprojectserver.command;

import java.util.Map;

/**
 * Outbound MQTT dispatch seam (System Design §5.5/§5.8: {@code PUBLISH iot/command/{device_id}}
 * QoS 1). Owned by {@code command} so this module has no compile dependency on {@code mqtt} —
 * {@code mqtt} depends on {@code command} (for {@code CommandService}, consuming acks), and
 * implements this interface to close the loop, mirroring the
 * {@code telemetry.RuleEventPublisher} seam.
 */
public interface CommandDispatcher {

  void dispatch(String targetDeviceId, String commandId, String deviceType, String action,
               Map<String, Object> parameters);
}
