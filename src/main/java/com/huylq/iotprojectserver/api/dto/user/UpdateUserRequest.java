package com.huylq.iotprojectserver.api.dto.user;

import com.huylq.iotprojectserver.security.Role;

import com.huylq.iotprojectserver.security.user.User;

public record UpdateUserRequest(Role role, User.Status status) {
}
