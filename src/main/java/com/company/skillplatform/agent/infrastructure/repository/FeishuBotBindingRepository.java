package com.company.skillplatform.agent.infrastructure.repository;

import com.company.skillplatform.agent.infrastructure.entity.FeishuBotBindingEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeishuBotBindingRepository extends JpaRepository<FeishuBotBindingEntity, Long> {
    Optional<FeishuBotBindingEntity> findByOwnerUserIdAndScopeKey(Long ownerUserId, String scopeKey);
}
