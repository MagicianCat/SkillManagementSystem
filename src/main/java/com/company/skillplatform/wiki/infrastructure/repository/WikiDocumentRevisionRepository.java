package com.company.skillplatform.wiki.infrastructure.repository;

import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiDocumentRevisionRepository extends JpaRepository<WikiDocumentRevisionEntity, Long> {
    List<WikiDocumentRevisionEntity> findByDocumentIdOrderByRevisionNoDesc(Long documentId, Pageable pageable);
    Optional<WikiDocumentRevisionEntity> findByDocumentIdAndRevisionNo(Long documentId, int revisionNo);
}
