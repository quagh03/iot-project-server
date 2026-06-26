package com.huylq.iotprojectserver.api.dto.user;

import com.huylq.iotprojectserver.security.user.User;

public record UpdateUserRequest(User.Role role, User.Status status) {
}
