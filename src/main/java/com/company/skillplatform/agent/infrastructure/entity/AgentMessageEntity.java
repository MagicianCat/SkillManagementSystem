package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity
@Table(name="agent_message")
public class AgentMessageEntity extends BaseJpaEntity {
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="session_id") private AgentSessionEntity session;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="run_id") private AgentRunEntity run;
    @Column(name="sequence_no", nullable=false) private long sequenceNo;
    @Column(nullable=false, length=32) private String role;
    @Lob @Column(nullable=false, columnDefinition="mediumtext") private String content;
    @Column(nullable=false, length=32) private String status;
    protected AgentMessageEntity() {}
    public AgentMessageEntity(AgentSessionEntity session, AgentRunEntity run, long sequenceNo, String role, String content, String status) {
        this.session=session;this.run=run;this.sequenceNo=sequenceNo;this.role=role;this.content=content;this.status=status;
    }
    public long getSequenceNo(){return sequenceNo;} public String getRole(){return role;} public String getContent(){return content;} public String getStatus(){return status;}
    public AgentRunEntity getRun(){return run;}
}
