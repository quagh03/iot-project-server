package com.huylq.iotprojectserver.security.user;


import java.util.List;
import java.util.UUID;

public interface UserService {

    User create(String username, String password, User.Role role, User.Role callerRole, String callerId, String ip);

    User get(UUID id);

    List<User> list(User.Role role, User.Status status, int offset, int limit);

    long count(User.Role role, User.Status status);

    User update(UUID id, User.Role newRole, User.Status newStatus,
                User.Role callerRole, String callerId, String ip);

    void softDelete(UUID id, User.Role callerRole, String callerId, String ip);

    void resetPassword(UUID id, String newPassword, User.Role callerRole, String callerId, String ip);
}
