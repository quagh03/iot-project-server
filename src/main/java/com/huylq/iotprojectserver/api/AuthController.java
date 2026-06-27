package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.auth.LoginRequest;
import com.huylq.iotprojectserver.api.dto.auth.LoginResponse;
import com.huylq.iotprojectserver.api.dto.auth.LogoutRequest;
import com.huylq.iotprojectserver.api.dto.auth.RefreshRequest;
import com.huylq.iotprojectserver.security.user.AuthService;
import com.huylq.iotprojectserver.security.user.IssuedTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

  private final AuthService authService;

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    String ip = clientIp(http);
    log.info("POST /auth/login username='{}'", req.username());
    IssuedTokens t = authService.login(req.username(), req.password(), ip);
    return ResponseEntity.ok(toLoginResponse(t));
  }

  @PostMapping("/refresh")
  public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
    String ip = clientIp(http);
    log.info("POST /auth/refresh");
    IssuedTokens t = authService.refresh(req.refreshToken(), ip);
    return ResponseEntity.ok(toLoginResponse(t));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest req, HttpServletRequest http) {
    String ip = clientIp(http);
    log.info("POST /auth/logout");
    authService.logout(req.refreshToken(), ip);
    return ResponseEntity.noContent().build();
  }

  private static LoginResponse toLoginResponse(IssuedTokens t) {
    return new LoginResponse(t.accessToken(), "Bearer", t.accessTokenTtlSeconds(),
        t.refreshToken(), t.role());
  }

  static String clientIp(HttpServletRequest req) {
    String fwd = req.getHeader("X-Forwarded-For");
    if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
    return req.getRemoteAddr();
  }
}
