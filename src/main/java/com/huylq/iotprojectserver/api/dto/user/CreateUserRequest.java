package com.huylq.iotprojectserver.api.dto.user;

import com.huylq.iotprojectserver.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
    @NotBlank @Size(min = 3, max = 64) String username,
    @NotBlank @Size(min = 8, max = 128) String password,
    @NotNull Role role) {
}
