package com.company.skillplatform.project.infrastructure.repository;

import com.company.skillplatform.project.infrastructure.entity.DocumentAgentSessionEntity;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;

public interface DocumentAgentSessionRepository extends JpaRepository<DocumentAgentSessionEntity, Long> {
    @EntityGraph(attributePaths = {"project", "owner", "document"})
    Optional<DocumentAgentSessionEntity> findBySessionKey(String sessionKey);
    @EntityGraph(attributePaths = {"project", "owner", "document"})
    Optional<DocumentAgentSessionEntity> findBySessionKeyAndOwnerId(String sessionKey, Long ownerId);
    Optional<DocumentAgentSessionEntity> findByOwnerIdAndProjectProjectKeyAndIdempotencyKey(Long ownerId, String projectKey, String idempotencyKey);
    @EntityGraph(attributePaths = {"project", "owner", "document"})
    Optional<DocumentAgentSessionEntity> findFirstByProjectProjectKeyAndStageIdAndStatusOrderByTimeCreatedDesc(String projectKey, Long stageId, String status);
    @EntityGraph(attributePaths = {"project", "owner", "document"})
    List<DocumentAgentSessionEntity> findByProjectProjectKeyAndOwnerIdOrderByLastActivityAtDesc(String projectKey, Long ownerId, Pageable pageable);
    @EntityGraph(attributePaths = {"project", "owner", "document"})
    List<DocumentAgentSessionEntity> findByOwnerIdOrderByLastActivityAtDesc(Long ownerId, Pageable pageable);
    @Query("select s from DocumentAgentSessionEntity s join fetch s.project p join fetch s.owner where s.sessionKey = :key")
    Optional<DocumentAgentSessionEntity> findForMcp(@Param("key") String key);
    List<DocumentAgentSessionEntity> findByStatusInAndLastActivityAtBefore(Collection<String> statuses, java.time.Instant cutoff);
    @Modifying
    @Query("delete from DocumentAgentSessionEntity s where s.lastActivityAt < :cutoff")
    int deleteHistoryBefore(@Param("cutoff") java.time.Instant cutoff);
}
