package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.ProjectDocumentSourceEntity;
import com.company.skillplatform.project.infrastructure.entity.ProjectDocumentSourceEntity.Key;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectDocumentSourceRepository extends JpaRepository<ProjectDocumentSourceEntity, Key> {
    void deleteByDocumentId(Long documentId);
}
