package com.huylq.iotprojectserver.common.ratelimit;

import com.huylq.iotprojectserver.common.error.ErrorType;
import com.huylq.iotprojectserver.security.JwtService;
import com.huylq.iotprojectserver.security.detection.SecurityDetectionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;

/**
 * Applies per-category rate limits (data spec §29). Classifies the caller from URL +
 * principal type, then asks the {@link RateLimiter} for a decision and writes 429 on deny.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimiter rateLimiter;
  private final RateLimitConfig config;
  private final ObjectMapper objectMapper;
  private final SecurityDetectionService securityDetection;

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (!config.enabled()) {
      chain.doFilter(req, res);
      return;
    }
    Category category = classify(req);
    if (category == null) {
      chain.doFilter(req, res);
      return;
    }
    String key = category.name() + ":" + keyFor(req, category);
    RateLimiter.Decision d = rateLimiter.tryAcquire(key, limitFor(category));

    res.setHeader("RateLimit-Limit", String.valueOf(d.limit()));
    res.setHeader("RateLimit-Remaining", String.valueOf(d.remaining()));
    res.setHeader("RateLimit-Reset", String.valueOf(d.resetSeconds()));

    if (!d.allowed()) {
      res.setHeader("Retry-After", String.valueOf(d.resetSeconds()));
      securityDetection.recordRateLimitDenial(category.name(), keyFor(req, category));
      writeProblem(res, req.getRequestURI(), d.resetSeconds());
      return;
    }
    chain.doFilter(req, res);
  }

  private Category classify(HttpServletRequest req) {
    String uri = req.getRequestURI();
    if (uri.startsWith("/api/v1/auth/") || uri.equals("/api/v1/oauth2/token")) {
      return Category.AUTH;
    }
    if (uri.startsWith("/api/v1/telemetry") || uri.startsWith("/api/v1/heartbeat")) {
      return Category.TELEMETRY;
    }
    if (uri.startsWith("/api/v1/")) {
      Jwt jwt = currentJwt();
      if (jwt == null) return null;
      return JwtService.TYPE_DEVICE.equals(jwt.getClaimAsString("typ"))
          ? Category.DEVICE
          : Category.USER;
    }
    return null;
  }

  private String keyFor(HttpServletRequest req, Category category) {
    if (category == Category.AUTH) {
      return clientIp(req);
    }
    // TELEMETRY must key by device identity, not IP (API §1) — it's the control against
    // sensor flooding/blinding, which needs the limit attributed to the flooding device
    // rather than a shared/NATed IP.
    Jwt jwt = currentJwt();
    return jwt != null ? jwt.getSubject() : clientIp(req);
  }

  private int limitFor(Category category) {
    return switch (category) {
      case AUTH -> config.authPerMinute();
      case USER -> config.userPerMinute();
      case DEVICE -> config.devicePerMinute();
      case TELEMETRY -> config.telemetryPerMinute();
    };
  }

  private static Jwt currentJwt() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth instanceof JwtAuthenticationToken t) return t.getToken();
    return null;
  }

  private static String clientIp(HttpServletRequest req) {
    String fwd = req.getHeader("X-Forwarded-For");
    if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
    return req.getRemoteAddr();
  }

  private void writeProblem(HttpServletResponse res, String path, long retryAfter) throws IOException {
    ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
    pd.setType(ErrorType.RATE_LIMITED.uri());
    pd.setTitle("Too Many Requests");
    pd.setDetail("Rate limit exceeded; retry after " + retryAfter + " seconds");
    pd.setInstance(URI.create(path));
    res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    res.getWriter().write(objectMapper.writeValueAsString(pd));
  }

  private enum Category {AUTH, USER, DEVICE, TELEMETRY}
}
