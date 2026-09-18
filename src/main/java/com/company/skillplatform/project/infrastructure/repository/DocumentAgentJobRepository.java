package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.*;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DocumentAgentJobRepository extends JpaRepository<DocumentAgentJobEntity, Long> {
    @EntityGraph(attributePaths = {"session", "session.project", "session.owner", "session.document"})
    List<DocumentAgentJobEntity> findTop20ByStatusOrderByTimeCreatedAsc(String status);
    @EntityGraph(attributePaths = {"session", "session.project", "session.owner", "session.document"})
    @Query("select j from DocumentAgentJobEntity j join fetch j.session s where (j.status = 'QUEUED' or (j.status = 'RETRY_WAIT' and (j.nextAttemptAt is null or j.nextAttemptAt <= :now)) or (j.status = 'DISPATCHING' and j.dispatchLeaseUntil <= :now)) order by j.timeCreated asc")
    List<DocumentAgentJobEntity> findDispatchable(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);
    @EntityGraph(attributePaths = {"session", "session.project", "session.owner", "session.document"})
    Optional<DocumentAgentJobEntity> findByJobKey(String jobKey);
    Optional<DocumentAgentJobEntity> findBySessionAndIdempotencyKey(DocumentAgentSessionEntity session, String key);
    Optional<DocumentAgentJobEntity> findFirstBySessionAndStatusInOrderBySequenceNoDesc(DocumentAgentSessionEntity session, Collection<String> statuses);
    Optional<DocumentAgentJobEntity> findTopBySessionOrderBySequenceNoDesc(DocumentAgentSessionEntity session);
    @EntityGraph(attributePaths = {"requestedBy"})
    List<DocumentAgentJobEntity> findBySessionOrderBySequenceNoAsc(DocumentAgentSessionEntity session);
    @Query("select j from DocumentAgentJobEntity j join fetch j.session s join fetch s.project where j.runtimeJobId = :runtimeJobId")
    Optional<DocumentAgentJobEntity> findByRuntimeJobId(@Param("runtimeJobId") String runtimeJobId);
}
