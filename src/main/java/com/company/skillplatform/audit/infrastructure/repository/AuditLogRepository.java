package com.company.skillplatform.audit.infrastructure.repository;

import com.company.skillplatform.audit.infrastructure.entity.AuditLogEntity;
import java.util.List;import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {List<AuditLogEntity> findByTargetTypeAndTargetIdOrderByTimeCreatedDesc(String targetType,Long targetId);
}
