package com.company.skillplatform.agent.infrastructure.repository;
import com.company.skillplatform.agent.infrastructure.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentMessageRepository extends JpaRepository<AgentMessageEntity,Long>{
 List<AgentMessageEntity> findBySessionOrderBySequenceNoAsc(AgentSessionEntity session);
 long countBySession(AgentSessionEntity session);
}
