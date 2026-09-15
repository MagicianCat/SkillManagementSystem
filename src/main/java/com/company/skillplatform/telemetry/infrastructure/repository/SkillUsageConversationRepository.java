package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageConversationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface SkillUsageConversationRepository extends JpaRepository<SkillUsageConversationEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SkillUsageConversationEntity> findByUserIdAndClientSessionId(Long userId, String clientSessionId);

    @Query("select c from SkillUsageConversationEntity c where c.user.id = :userId and c.clientSessionId = :clientSessionId")
    Optional<SkillUsageConversationEntity> findForRead(Long userId, String clientSessionId);
}
