package com.company.skillplatform.agent.infrastructure.repository;
import com.company.skillplatform.agent.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
public interface AgentRunRepository extends JpaRepository<AgentRunEntity,Long>{
 @EntityGraph(attributePaths="session")
 Optional<AgentRunEntity> findByRunKey(String key);
 @EntityGraph(attributePaths="session")
 Optional<AgentRunEntity> findByRunKeyAndSessionOwnerUserId(String key,Long owner);
 Optional<AgentRunEntity> findFirstBySessionAndStatusInOrderByRunNoDesc(AgentSessionEntity session,Collection<String> statuses);
 Optional<AgentRunEntity> findFirstBySessionOrderByRunNoDesc(AgentSessionEntity session);
 long countBySession(AgentSessionEntity session);
 @Modifying(clearAutomatically=true, flushAutomatically=true) @Transactional
 @Query("update AgentRunEntity r set r.status='RUNNING',r.runtimeRunId=:runKey,r.startedAt=:startedAt,r.versionNo=r.versionNo+1 where r.runKey=:runKey and r.status='PENDING'")
 int claimPendingRun(String runKey, java.time.Instant startedAt);
 @Modifying(clearAutomatically=true, flushAutomatically=true) @Transactional
 @Query("update AgentRunEntity r set r.status='CANCELLED',r.finishedAt=:finishedAt,r.versionNo=r.versionNo+1 where r.runKey=:runKey and r.status in ('PENDING','RUNNING')")
 int cancelActiveRun(String runKey, java.time.Instant finishedAt);
}
