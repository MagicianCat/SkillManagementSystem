package com.company.skillplatform.audit.application;

import com.company.skillplatform.audit.infrastructure.entity.AuditLogEntity;
import com.company.skillplatform.audit.infrastructure.repository.AuditLogRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;import java.util.List;

@Service
public class AuditService {
    private final AuditLogRepository auditLogs;

    public AuditService(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    public void success(String eventType, IamUserEntity actor, String targetType, Long targetId,
                        String requestId, Map<String, Object> before, Map<String, Object> after,
                        Map<String, Object> metadata) {
        auditLogs.save(new AuditLogEntity(eventType, actor, targetType, targetId,
                normalizeRequestId(requestId), before, after, metadata));
    }

    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) return "unknown";
        return requestId.substring(0, Math.min(requestId.length(), 64));
    }
    @PreAuthorize("hasAuthority('admin:audit')") public List<AuditLogEntity> list(String targetType,Long targetId){return auditLogs.findByTargetTypeAndTargetIdOrderByTimeCreatedDesc(targetType,targetId);}
}
