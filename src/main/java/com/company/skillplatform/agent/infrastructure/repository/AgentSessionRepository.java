package com.company.skillplatform.agent.infrastructure.repository;
import com.company.skillplatform.agent.infrastructure.entity.AgentSessionEntity;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AgentSessionRepository extends JpaRepository<AgentSessionEntity,Long>{
 Optional<AgentSessionEntity> findBySessionKeyAndOwnerUserId(String key,Long owner);
 Optional<AgentSessionEntity> findBySessionKey(String key);
 List<AgentSessionEntity> findByOwnerUserIdAndStatusNotOrderByLastMessageAtDescTimeCreatedDesc(Long owner, String status);
 List<AgentSessionEntity> findByStatusAndDeletedAtBefore(String status, java.time.Instant cutoff);
}
