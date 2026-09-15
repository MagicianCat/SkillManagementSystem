package com.company.skillplatform.wiki.infrastructure.repository;

import com.company.skillplatform.wiki.domain.WikiReviewStatus;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentReviewEntity;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;

public interface WikiDocumentReviewRepository extends JpaRepository<WikiDocumentReviewEntity, Long>, JpaSpecificationExecutor<WikiDocumentReviewEntity> {
    long countByDocumentId(Long documentId);
    Optional<WikiDocumentReviewEntity> findByDocumentIdAndStatus(Long documentId, WikiReviewStatus status);
    boolean existsByDocumentIdAndStatus(Long documentId, WikiReviewStatus status);
}
