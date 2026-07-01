package com.huylq.iotprojectserver.health;

import java.util.List;

/**
 * Health module's read-side published interface (System Design §9) — callers outside
 * this module (e.g. {@code api}) go through here, never {@link DeviceHealthRepository}
 * directly.
 */
public interface HealthService {

  List<ZoneConnectivityRow> connectivity(String zone);
}
