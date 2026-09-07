package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_recommendation")
public class AgentRecommendationEntity extends BaseJpaEntity {
    @Column(name="run_ref", nullable=false, unique=true, length=64) private String runRef;
    @Column(nullable=false, length=2000) private String summary;
    @Lob @Column(nullable=false, columnDefinition="text") private String payload;
    protected AgentRecommendationEntity() {}
    public AgentRecommendationEntity(String runRef, String summary, String payload) { this.runRef=runRef; this.summary=summary; this.payload=payload; }
}
