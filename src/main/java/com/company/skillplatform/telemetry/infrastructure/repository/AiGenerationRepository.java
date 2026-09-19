package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGenerationRepository extends JpaRepository<AiGenerationEntity, Long> {
    /** 幂等键：user_id + hook_generation_id。 */
    Optional<AiGenerationEntity> findByUser_IdAndHookGenerationId(Long userId, String hookGenerationId);
}
