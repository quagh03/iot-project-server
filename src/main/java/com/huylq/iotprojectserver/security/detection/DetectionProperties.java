package com.huylq.iotprojectserver.security.detection;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Burst thresholds for the §7 detection & incident-response signals. No values are
 * pinned by the design docs — conservative defaults tuned for an office-scale deployment
 * (tens of devices/users), not internet-scale traffic.
 */
@ConfigurationProperties("iot.detection")
public record DetectionProperties(
    int authFailureBurstThreshold,
    int rateLimitSpikeThreshold,
    int forbiddenSpikeThreshold,
    int commandTimeoutBurstThreshold) {

  public DetectionProperties {
    if (authFailureBurstThreshold <= 0) authFailureBurstThreshold = 5;
    if (rateLimitSpikeThreshold <= 0) rateLimitSpikeThreshold = 10;
    if (forbiddenSpikeThreshold <= 0) forbiddenSpikeThreshold = 10;
    if (commandTimeoutBurstThreshold <= 0) commandTimeoutBurstThreshold = 3;
  }
}
