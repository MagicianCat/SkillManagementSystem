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

    /**
     * 按客户端稳定 Generation ID 精确关联，避免同一 session 的多轮事件串到最新一轮。
     * 该更新在 Skill/Generation 任一侧后到时都可安全重复执行。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE skill_usage_event e JOIN ai_generation_event g "
            + "ON g.user_id=e.user_id AND g.hook_generation_id=e.generation_id "
            + "SET e.ai_generation_id=g.id "
            + "WHERE e.user_id=:userId AND e.generation_id=:generationId AND e.ai_generation_id IS NULL",
            nativeQuery = true)
    int linkGenerationByExternalId(@Param("userId") Long userId, @Param("generationId") String generationId);
}
