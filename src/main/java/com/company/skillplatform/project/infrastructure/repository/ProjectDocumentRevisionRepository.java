package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.ProjectDocumentRevisionEntity;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectDocumentRevisionRepository extends JpaRepository<ProjectDocumentRevisionEntity, Long> {
    List<ProjectDocumentRevisionEntity> findByDocumentIdOrderByRevisionNoDesc(Long documentId, Pageable pageable);
    long countByDocumentId(Long documentId);
    Optional<ProjectDocumentRevisionEntity> findByIdAndDocumentId(Long id, Long documentId);
}
