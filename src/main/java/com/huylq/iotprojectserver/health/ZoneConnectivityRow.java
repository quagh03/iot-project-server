package com.huylq.iotprojectserver.health;

/**
 * Spring Data interface projection for {@code DeviceHealthRepository.rollUpByZone}.
 */
public interface ZoneConnectivityRow {

  String getZone();

  Long getOnline();

  Long getOffline();

  Long getTotal();
}
