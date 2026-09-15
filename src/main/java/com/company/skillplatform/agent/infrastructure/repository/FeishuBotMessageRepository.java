package com.company.skillplatform.agent.infrastructure.repository;

import com.company.skillplatform.agent.infrastructure.entity.FeishuBotMessageEntity;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FeishuBotMessageRepository extends JpaRepository<FeishuBotMessageEntity, Long> {
    Optional<FeishuBotMessageEntity> findByMessageId(String messageId);
    @Query("select m from FeishuBotMessageEntity m where m.status in :statuses and (m.nextRetryAt is null or m.nextRetryAt <= :now) order by m.timeCreated asc")
    List<FeishuBotMessageEntity> findReady(Collection<String> statuses, Instant now, org.springframework.data.domain.Pageable pageable);
    Optional<FeishuBotMessageEntity> findByAgentRunKey(String runKey);
}
