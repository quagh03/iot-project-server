package com.huylq.iotprojectserver.security.device;

import com.huylq.iotprojectserver.common.error.ApiException;
import com.huylq.iotprojectserver.common.error.ErrorType;
import com.huylq.iotprojectserver.common.time.Clocks;
import com.huylq.iotprojectserver.security.JwtConfig;
import com.huylq.iotprojectserver.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
class DeviceTokenServiceImpl implements DeviceTokenService {

    private final DeviceCredentialRepository credRepo;
    private final DeviceScopeRepository scopeRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtConfig jwtConfig;

    @Override
    @Transactional(readOnly = true)
    public DeviceTokenResult mint(String clientId, String clientSecret, Set<String> requestedScopes) {
        DeviceCredential cred = credRepo.findByClientId(clientId)
                .orElseThrow(() -> badClient());

        if (!verifySecret(cred, clientSecret)) throw badClient();

        Set<String> stored = scopeRepo.findByDeviceId(cred.getDeviceId()).stream()
                .map(DeviceScope::getScope)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> granted = (requestedScopes == null || requestedScopes.isEmpty())
                ? stored
                : intersection(stored, requestedScopes);

        String token = jwtService.issueDeviceToken(cred.getDeviceId(), granted);
        return new DeviceTokenResult(token, jwtConfig.deviceTokenTtl().getSeconds(), granted);
    }

    private boolean verifySecret(DeviceCredential cred, String presented) {
        if (passwordEncoder.matches(presented, cred.getClientSecretHash())) return true;
        // Honour rotation grace window: the previous secret stays valid until grace_expires_at.
        if (cred.getPreviousSecretHash() != null
                && cred.getGraceExpiresAt() != null
                && cred.getGraceExpiresAt().isAfter(Clocks.nowUtc())
                && passwordEncoder.matches(presented, cred.getPreviousSecretHash())) {
            return true;
        }
        return false;
    }

    private static Set<String> intersection(Set<String> stored, Set<String> requested) {
        Set<String> out = new LinkedHashSet<>(stored);
        out.retainAll(requested);
        return out;
    }

    private static ApiException badClient() {
        return new ApiException(ErrorType.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED,
                "Invalid client credentials");
    }
}
