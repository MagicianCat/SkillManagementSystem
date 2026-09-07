package com.company.skillplatform.agent.infrastructure.repository;
import com.company.skillplatform.agent.infrastructure.entity.AgentSessionEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity,Long>{
 Optional<AgentSessionEntity> findBySessionKeyAndOwnerUserId(String key,Long owner);
 List<AgentSessionEntity> findByOwnerUserIdOrderByLastMessageAtDescTimeCreatedDesc(Long owner);
}
