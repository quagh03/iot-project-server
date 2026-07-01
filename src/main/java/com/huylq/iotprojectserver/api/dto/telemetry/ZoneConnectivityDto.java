package com.huylq.iotprojectserver.api.dto.telemetry;

import com.huylq.iotprojectserver.health.ZoneConnectivityRow;

/**
 * Zone online/offline roll-up (API §6 {@code GET /v1/connectivity}). Shape isn't pinned
 * by the design docs — chosen to directly back a "how many devices are up in this zone"
 * dashboard tile.
 */
public record ZoneConnectivityDto(String zone, long online, long offline, long total) {

  public static ZoneConnectivityDto from(ZoneConnectivityRow row) {
    return new ZoneConnectivityDto(row.getZone(), row.getOnline(), row.getOffline(), row.getTotal());
  }
}
