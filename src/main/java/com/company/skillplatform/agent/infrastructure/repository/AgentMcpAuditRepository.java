package com.company.skillplatform.agent.infrastructure.repository;

import com.company.skillplatform.agent.infrastructure.entity.AgentMcpAuditEntity;
import java.util.*;
import java.time.Instant;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentMcpAuditRepository extends JpaRepository<AgentMcpAuditEntity, Long> {
    Optional<AgentMcpAuditEntity> findByRunKeyAndCallId(String runKey, String callId);
    @Query("select a from AgentMcpAuditEntity a where (:runKey is null or a.runKey = :runKey) and (:sessionKey is null or a.sessionKey = :sessionKey) and (:toolName is null or a.toolName = :toolName) and (:status is null or a.status = :status) and (:sourceChannel is null or a.sourceChannel = :sourceChannel) and (:from is null or a.startedAt >= :from) and (:to is null or a.startedAt < :to) order by a.timeCreated desc")
    Page<AgentMcpAuditEntity> search(@Param("runKey") String runKey, @Param("sessionKey") String sessionKey,
                                     @Param("toolName") String toolName, @Param("status") String status,
                                     @Param("sourceChannel") String sourceChannel, @Param("from") Instant from,
                                     @Param("to") Instant to, Pageable pageable);
}
