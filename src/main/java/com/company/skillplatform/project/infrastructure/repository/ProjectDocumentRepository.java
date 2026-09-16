package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.ProjectDocumentEntity;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectDocumentRepository extends JpaRepository<ProjectDocumentEntity, Long> {
    Page<ProjectDocumentEntity> findByProjectIdAndStatusNotOrderByTimeUpdatedDesc(Long projectId, String status, Pageable pageable);
    Optional<ProjectDocumentEntity> findByIdAndProjectId(Long id, Long projectId);
    List<ProjectDocumentEntity> findByEverPublishedFalseAndStatusAndLastDraftActivityAtBefore(String status, Instant cutoff);
}
