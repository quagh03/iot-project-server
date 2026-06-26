package com.huylq.iotprojectserver.audit;

import com.huylq.iotprojectserver.common.time.Clocks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
class AuditServiceImpl implements AuditService {

    private final AuditLogRepository repo;

    /**
     * Writes in its own transaction so a rolled-back business operation still leaves an
     * audit trail of what was attempted (failed login, denied rotation, etc.).
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void append(String actor, AuditLog.ActorType actorType, AuditEvent event,
                       String target, Map<String, Object> detail, String ip) {
        AuditLog row = AuditLog.builder()
                .ts(Clocks.nowUtc())
                .actor(actor)
                .actorType(actorType)
                .event(event.code())
                .target(target)
                .detail(detail)
                .ip(ip)
                .build();
        repo.save(row);
    }
}
