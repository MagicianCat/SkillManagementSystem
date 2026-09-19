package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationSkillEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGenerationSkillRepository extends JpaRepository<AiGenerationSkillEntity, Long> {
    List<AiGenerationSkillEntity> findByGeneration_Id(Long generationId);
}
