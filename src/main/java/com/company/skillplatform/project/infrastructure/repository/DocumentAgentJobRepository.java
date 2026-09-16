package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DocumentAgentJobRepository extends JpaRepository<DocumentAgentJobEntity, Long> {
    @EntityGraph(attributePaths = {"session", "session.project", "session.owner", "session.document"})
    Optional<DocumentAgentJobEntity> findByJobKey(String jobKey);
    Optional<DocumentAgentJobEntity> findBySessionAndIdempotencyKey(DocumentAgentSessionEntity session, String key);
    Optional<DocumentAgentJobEntity> findFirstBySessionAndStatusInOrderBySequenceNoDesc(DocumentAgentSessionEntity session, Collection<String> statuses);
    Optional<DocumentAgentJobEntity> findTopBySessionOrderBySequenceNoDesc(DocumentAgentSessionEntity session);
    @Query("select j from DocumentAgentJobEntity j join fetch j.session s join fetch s.project where j.runtimeJobId = :runtimeJobId")
    Optional<DocumentAgentJobEntity> findByRuntimeJobId(@Param("runtimeJobId") String runtimeJobId);
}
