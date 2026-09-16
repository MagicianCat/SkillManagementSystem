package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface DocumentAgentJobEventRepository extends JpaRepository<DocumentAgentJobEventEntity, Long> {
    List<DocumentAgentJobEventEntity> findByJobAndSequenceNoGreaterThanOrderBySequenceNoAsc(DocumentAgentJobEntity job, long after);
    Optional<DocumentAgentJobEventEntity> findTopByJobOrderBySequenceNoDesc(DocumentAgentJobEntity job);
}
