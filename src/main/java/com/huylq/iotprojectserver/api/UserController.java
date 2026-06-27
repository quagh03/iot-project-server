package com.huylq.iotprojectserver.api;

import com.huylq.iotprojectserver.api.dto.user.CreateUserRequest;
import com.huylq.iotprojectserver.api.dto.user.PasswordResetRequest;
import com.huylq.iotprojectserver.api.dto.user.UpdateUserRequest;
import com.huylq.iotprojectserver.api.dto.user.UserDto;
import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.pagination.OffsetPage;
import com.huylq.iotprojectserver.common.pagination.PagedResponse;
import com.huylq.iotprojectserver.common.pagination.PaginationConfig;
import com.huylq.iotprojectserver.security.Role;
import com.huylq.iotprojectserver.security.user.User;
import com.huylq.iotprojectserver.security.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

  private final UserService userService;
  private final PaginationConfig pagination;

  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<PagedResponse<UserDto>> list(@RequestParam(required = false) Role role,
                                                     @RequestParam(required = false) User.Status status,
                                                     @RequestParam(defaultValue = "0") int offset,
                                                     @RequestParam(required = false) Integer pageSize) {
    log.debug("GET /users role={} status={} offset={} pageSize={}", role, status, offset, pageSize);
    int limit = pagination.clamp(pageSize);
    List<UserDto> items = userService.list(role, status, offset, limit).stream()
        .map(UserDto::from).toList();
    long total = userService.count(role, status);
    return ResponseEntity.ok(PagedResponse.offset(items, new OffsetPage(offset, limit, total)));
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<UserDto> create(@Valid @RequestBody CreateUserRequest req, @AuthenticationPrincipal Jwt caller,
                                        HttpServletRequest http) {
    log.info("POST /users username='{}' role={} caller={}", req.username(), req.role(), caller.getSubject());
    User u = userService.create(req.username(), req.password(), req.role(),
        callerRole(caller), caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.created(URI.create("/api/v1/users/" + u.getId())).body(UserDto.from(u));
  }

  @GetMapping("/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<UserDto> get(@PathVariable UUID userId) {
    log.debug("GET /users/{}", userId);
    return ResponseEntity.ok(UserDto.from(userService.get(userId)));
  }

  @PatchMapping("/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<UserDto> update(@PathVariable UUID userId, @RequestBody UpdateUserRequest req,
                                        @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("PATCH /users/{} role={} status={} caller={}", userId, req.role(), req.status(), caller.getSubject());
    User u = userService.update(userId, req.role(), req.status(), callerRole(caller),
        caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.ok(UserDto.from(u));
  }

  @DeleteMapping("/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable UUID userId, @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("DELETE /users/{} caller={}", userId, caller.getSubject());
    userService.softDelete(userId, callerRole(caller), caller.getSubject(),
        AuthController.clientIp(http));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{userId}/password-reset")
  @PreAuthorize("hasRole('ADMIN') or #userId.toString() == authentication.token.subject")
  public ResponseEntity<Void> resetPassword(@PathVariable UUID userId,
                                            @Valid @RequestBody PasswordResetRequest req,
                                            @AuthenticationPrincipal Jwt caller, HttpServletRequest http) {
    log.info("POST /users/{}/password-reset caller={}", userId, caller.getSubject());
    userService.resetPassword(userId, req.newPassword(), callerRole(caller),
        caller.getSubject(), AuthController.clientIp(http));
    return ResponseEntity.noContent().build();
  }

  private static Role callerRole(Jwt jwt) {
    String role = jwt.getClaimAsString("role");
    if (role == null) throw ApiException.forbidden("Missing role on caller token");
    return Role.valueOf(role);
  }
}
