package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.SkillUsageEventEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface SkillUsageEventRepository extends JpaRepository<SkillUsageEventEntity, Long> {
    Optional<SkillUsageEventEntity> findByEventUuid(String eventUuid);
    List<SkillUsageEventEntity> findTop50ByConversationStatusAndConversationAttemptsLessThanOrderByTimeCreatedAsc(String status, int attempts);

    /** 把同一会话内尚未关联 Generation 的 Skill 事件挂到指定 Generation。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SkillUsageEventEntity e set e.aiGenerationId = :generationId "
            + "where e.user.id = :userId and e.clientSessionId = :sessionId and e.aiGenerationId is null")
    int linkSessionToGeneration(@Param("userId") Long userId, @Param("sessionId") String sessionId,
            @Param("generationId") Long generationId);
}

