package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "document_agent_job_event", uniqueConstraints = @UniqueConstraint(name = "uk_document_agent_event_sequence", columnNames = {"job_id", "sequence_no"}))
public class DocumentAgentJobEventEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "job_id", nullable = false) private DocumentAgentJobEntity job;
    @Column(name = "sequence_no", nullable = false) private long sequenceNo;
    @Column(name = "runtime_event_id", length = 128) private String runtimeEventId;
    @Column(name = "event_type", nullable = false, length = 64) private String eventType;
    @Lob @Column(nullable = false, columnDefinition = "json") private String payload;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;

    protected DocumentAgentJobEventEntity() {}
    public DocumentAgentJobEventEntity(DocumentAgentJobEntity job, long sequenceNo, String runtimeEventId, String eventType, String payload, Instant occurredAt) {
        this.job = job; this.sequenceNo = sequenceNo; this.runtimeEventId = runtimeEventId; this.eventType = eventType; this.payload = payload; this.occurredAt = occurredAt;
    }
    public DocumentAgentJobEntity getJob() { return job; }
    public long getSequenceNo() { return sequenceNo; }
    public String getRuntimeEventId() { return runtimeEventId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
}
