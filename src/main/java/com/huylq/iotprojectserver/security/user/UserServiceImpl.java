package com.huylq.iotprojectserver.security.user;

import com.huylq.iotprojectserver.security.Role;

import com.huylq.iotprojectserver.audit.AuditEvent;
import com.huylq.iotprojectserver.audit.AuditService;
import com.huylq.iotprojectserver.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class UserServiceImpl implements UserService {

    private final UserRepository userRepo;
    private final RefreshTokenRepository refreshRepo;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    @Override
    @Transactional
    public User create(String username, String password, Role role,
                       Role callerRole, String callerId, String ip) {
        requireAuthorityToGrant(callerRole, role);
        if (userRepo.existsByUsername(username)) {
            throw ApiException.conflict("Username already exists");
        }
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .status(User.Status.ACTIVE)
                .build();
        User saved = userRepo.save(user);
        audit.user(callerId, AuditEvent.USER_CREATE, saved.getId().toString(),
                Map.of("username", username, "role", role.name()), ip);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public User get(UUID id) {
        return userRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> list(Role role, User.Status status, int offset, int limit) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        var page = userRepo.findAll(filter(role, status),
                PageRequest.of(offset / Math.max(1, limit), limit, sort));
        return page.getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(Role role, User.Status status) {
        return userRepo.count(filter(role, status));
    }

    @Override
    @Transactional
    public User update(UUID id, Role newRole, User.Status newStatus,
                       Role callerRole, String callerId, String ip) {
        User user = get(id);
        if (newRole != null && newRole != user.getRole()) {
            requireAuthorityToGrant(callerRole, newRole);
            requireAuthorityToGrant(callerRole, user.getRole()); // also: can't demote above your level
            user.setRole(newRole);
        }
        if (newStatus != null) {
            user.setStatus(newStatus);
            if (newStatus == User.Status.DISABLED) {
                refreshRepo.revokeAllForUser(user.getId());
            }
        }
        audit.user(callerId, AuditEvent.USER_UPDATE, id.toString(),
                Map.of("newRole", String.valueOf(newRole),
                       "newStatus", String.valueOf(newStatus)), ip);
        return user;
    }

    @Override
    @Transactional
    public void softDelete(UUID id, Role callerRole, String callerId, String ip) {
        User user = get(id);
        requireAuthorityToGrant(callerRole, user.getRole()); // need authority over the target's level
        user.setStatus(User.Status.DISABLED);
        refreshRepo.revokeAllForUser(user.getId());
        audit.user(callerId, AuditEvent.USER_DELETE, id.toString(), null, ip);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, String newPassword, Role callerRole,
                              String callerId, String ip) {
        User user = get(id);
        if (!callerId.equals(id.toString())) {
            requireAuthorityToGrant(callerRole, user.getRole());
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshRepo.revokeAllForUser(user.getId());
        audit.user(callerId, AuditEvent.USER_PASSWORD_RESET, id.toString(), null, ip);
    }

    /**
     * Authority rule (API §3):
     * <ul>
     *   <li>{@code SUPER_ADMIN} can grant any role (including SUPER_ADMIN / ADMIN).</li>
     *   <li>{@code ADMIN} may only manage {@code OPERATOR} / {@code VIEWER}.</li>
     *   <li>Anything else → {@code 403}.</li>
     * </ul>
     */
    private static void requireAuthorityToGrant(Role caller, Role target) {
        if (caller == Role.SUPER_ADMIN) return;
        if (caller == Role.ADMIN
                && (target == Role.OPERATOR || target == Role.VIEWER)) return;
        throw ApiException.forbidden("Caller may not manage role " + target);
    }

    private static Specification<User> filter(Role role, User.Status status) {
        return (root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (role != null)   preds.add(cb.equal(root.get("role"), role));
            if (status != null) preds.add(cb.equal(root.get("status"), status));
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
