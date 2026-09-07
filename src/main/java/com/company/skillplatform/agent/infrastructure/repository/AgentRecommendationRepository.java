package com.company.skillplatform.agent.infrastructure.repository;

import com.company.skillplatform.agent.infrastructure.entity.AgentRecommendationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRecommendationRepository extends JpaRepository<AgentRecommendationEntity, Long> {}
