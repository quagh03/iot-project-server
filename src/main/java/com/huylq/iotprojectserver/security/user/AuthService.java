package com.huylq.iotprojectserver.security.user;

public interface AuthService {

  IssuedTokens login(String username, String password, String ip);

  IssuedTokens refresh(String refreshToken, String ip);

  void logout(String refreshToken, String ip);
}
