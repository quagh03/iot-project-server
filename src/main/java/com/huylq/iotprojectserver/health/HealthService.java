package com.huylq.iotprojectserver.health;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Health module's published interface (System Design §9) — callers outside this module
 * (e.g. {@code api}, {@code telemetry}, {@code mqtt}) go through here, never
 * {@link DeviceHealthRepository} directly.
 */
public interface HealthService {

  List<ZoneConnectivityRow> connectivity(String zone);

  /**
   * Heartbeat ingest funnel (both MQTT and HTTP) — upserts the full health row
   * (connection status, last seen, resource metrics). Identity mismatch on the HTTP path
   * (authenticated device != body deviceId) → {@code 403}.
   */
  void upsertHeartbeat(HeartbeatCommand command);

  /**
   * A telemetry reading is itself liveness evidence — flips the gateway {@code ONLINE}
   * and bumps {@code last_seen} without touching the resource-metric columns a heartbeat
   * would report (System Design §6/§8: "heartbeat/telemetry flip it ONLINE").
   */
  void touchOnline(String deviceId, OffsetDateTime lastSeen);

  /**
   * Consumes the broker-published Last Will & Testament — flips a device {@code OFFLINE}
   * on an ungraceful disconnect (System Design §6/§8).
   */
  void markOffline(String deviceId);

  /**
   * Latest health row for one device (API §6 {@code GET /v1/devices/{deviceId}/health}).
   * No row yet (never reported) → {@code 404}.
   */
  DeviceHealth getHealth(String deviceId);
}
