package com.huylq.iotprojectserver.support;

import com.huylq.iotprojectserver.security.Role;

import tools.jackson.databind.ObjectMapper;
import com.huylq.iotprojectserver.security.user.User;
import com.huylq.iotprojectserver.security.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Test helper: seed users, parse access tokens out of login responses.
 */
@Component
public class SecurityTestSupport {

    @Autowired UserRepository userRepo;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper mapper;

    public User createUser(String username, String password, Role role) {
        return userRepo.save(User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .status(User.Status.ACTIVE)
                .build());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseJson(String json) throws Exception {
        return mapper.readValue(json, Map.class);
    }
}
