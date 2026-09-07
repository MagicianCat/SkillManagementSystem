package com.company.skillplatform.agent.infrastructure.repository;
import com.company.skillplatform.agent.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentRunRepository extends JpaRepository<AgentRunEntity,Long>{
 @EntityGraph(attributePaths="session")
 Optional<AgentRunEntity> findByRunKey(String key);
 @EntityGraph(attributePaths="session")
 Optional<AgentRunEntity> findByRunKeyAndSessionOwnerUserId(String key,Long owner);
 Optional<AgentRunEntity> findFirstBySessionAndStatusInOrderByRunNoDesc(AgentSessionEntity session,Collection<String> statuses);
 Optional<AgentRunEntity> findFirstBySessionOrderByRunNoDesc(AgentSessionEntity session);
 long countBySession(AgentSessionEntity session);
}
