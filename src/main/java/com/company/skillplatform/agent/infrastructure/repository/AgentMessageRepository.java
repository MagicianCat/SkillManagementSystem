package com.company.skillplatform.agent.infrastructure.repository;
import com.company.skillplatform.agent.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity,Long>{
 List<AgentMessageEntity> findBySessionOrderBySequenceNoAsc(AgentSessionEntity session);
 Optional<AgentMessageEntity> findByRunAndRole(AgentRunEntity run,String role);
 long countBySession(AgentSessionEntity session);
}
