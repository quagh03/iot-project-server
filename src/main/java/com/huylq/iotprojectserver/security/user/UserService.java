package com.huylq.iotprojectserver.security.user;

import com.huylq.iotprojectserver.security.Role;


import java.util.List;
import java.util.UUID;

public interface UserService {

  User create(String username, String password, Role role, Role callerRole, String callerId, String ip);

  User get(UUID id);

  List<User> list(Role role, User.Status status, int offset, int limit);

  long count(Role role, User.Status status);

  User update(UUID id, Role newRole, User.Status newStatus,
              Role callerRole, String callerId, String ip);

  void softDelete(UUID id, Role callerRole, String callerId, String ip);

  void resetPassword(UUID id, String newPassword, Role callerRole, String callerId, String ip);
}
