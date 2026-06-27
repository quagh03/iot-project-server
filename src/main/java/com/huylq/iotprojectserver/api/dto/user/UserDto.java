package com.huylq.iotprojectserver.api.dto.user;

import com.huylq.iotprojectserver.security.Role;

import com.huylq.iotprojectserver.security.user.User;

import java.time.OffsetDateTime;

/**
 * User wire shape. Deliberately omits {@code passwordHash}.
 */
public record UserDto(
    String id,
    String username,
    Role role,
    User.Status status,
    OffsetDateTime createdAt) {

  public static UserDto from(User u) {
    return new UserDto(u.getId().toString(), u.getUsername(), u.getRole(), u.getStatus(),
        u.getCreatedAt());
  }
}
