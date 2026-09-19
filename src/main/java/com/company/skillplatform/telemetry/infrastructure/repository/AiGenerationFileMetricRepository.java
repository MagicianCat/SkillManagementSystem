package com.company.skillplatform.telemetry.infrastructure.repository;

import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationFileMetricEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGenerationFileMetricRepository extends JpaRepository<AiGenerationFileMetricEntity, Long> {
    List<AiGenerationFileMetricEntity> findByGeneration_Id(Long generationId);
}
