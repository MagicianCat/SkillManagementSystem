package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.CommandUsageEventEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommandUsageEventRepository extends JpaRepository<CommandUsageEventEntity, Long> {
    Optional<CommandUsageEventEntity> findByEventUuid(String eventUuid);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE command_usage_event e JOIN ai_generation_event g "
            + "ON g.user_id=e.user_id AND g.hook_generation_id=e.generation_id "
            + "SET e.ai_generation_id=g.id "
            + "WHERE e.user_id=:userId AND e.generation_id=:generationId AND e.ai_generation_id IS NULL",
            nativeQuery = true)
    int linkGenerationByExternalId(@Param("userId") Long userId, @Param("generationId") String generationId);
}
