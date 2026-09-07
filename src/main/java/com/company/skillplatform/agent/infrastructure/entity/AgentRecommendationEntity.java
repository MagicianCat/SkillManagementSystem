package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "agent_recommendation")
public class AgentRecommendationEntity extends BaseJpaEntity {
    @Column(name="run_ref", nullable=false, unique=true, length=64) private String runRef;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="run_id") private AgentRunEntity run;
    @Column(nullable=false, length=2000) private String summary;
    @Column(nullable=false, length=32) private String status = "VALID";
    @Column(name="validated_at") private java.time.Instant validatedAt;
    @Lob @Column(nullable=false, columnDefinition="text") private String payload;
    protected AgentRecommendationEntity() {}
    public AgentRecommendationEntity(String runRef, String summary, String payload) { this.runRef=runRef; this.summary=summary; this.payload=payload; }
    public AgentRecommendationEntity(String runRef, AgentRunEntity run, String summary, String status, String payload) {
        this.runRef=runRef;this.run=run;this.summary=summary;this.status=status;this.payload=payload;this.validatedAt=java.time.Instant.now();
    }
    public String getRunRef(){return runRef;} public String getSummary(){return summary;} public String getStatus(){return status;} public String getPayload(){return payload;}
}
