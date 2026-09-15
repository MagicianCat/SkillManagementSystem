package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SkillUsageEventRepository extends JpaRepository<SkillUsageEventEntity, Long> {
    Optional<SkillUsageEventEntity> findByEventUuid(String eventUuid);
    List<SkillUsageEventEntity> findTop50ByConversationStatusAndConversationAttemptsLessThanOrderByTimeCreatedAsc(String status, int attempts);
}
