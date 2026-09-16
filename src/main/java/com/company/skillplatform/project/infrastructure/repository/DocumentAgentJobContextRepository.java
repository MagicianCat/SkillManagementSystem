package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface DocumentAgentJobContextRepository extends JpaRepository<DocumentAgentJobContextEntity, Long> {
    @EntityGraph(attributePaths = {"job", "artifact", "artifactRevision"})
    List<DocumentAgentJobContextEntity> findByJobOrderByOrdinalNoAsc(DocumentAgentJobEntity job);
    boolean existsByJobAndContextKindAndFeishuDocIdAndFeishuDocType(DocumentAgentJobEntity job, String kind, String docId, String docType);
}
