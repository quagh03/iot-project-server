package com.huylq.iotprojectserver.security.device;

import java.util.Set;

public interface DeviceTokenService {

  DeviceTokenResult mint(String clientId, String clientSecret, Set<String> requestedScopes);

  record DeviceTokenResult(String accessToken, long expiresInSeconds, Set<String> grantedScopes) {
  }
}
