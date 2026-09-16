package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_agent_job", uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_agent_job_idempotency", columnNames = {"session_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_document_agent_job_sequence", columnNames = {"session_id", "sequence_no"})
})
public class DocumentAgentJobEntity extends BaseJpaEntity {
    @Column(name = "job_key", nullable = false, unique = true, columnDefinition = "char(36)") private String jobKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "session_id", nullable = false) private DocumentAgentSessionEntity session;
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;
    @Column(name = "idempotency_key", nullable = false, length = 255) private String idempotencyKey;
    @Lob @Column(nullable = false, columnDefinition = "mediumtext") private String instruction;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "runtime_job_id", length = 128) private String runtimeJobId;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "error_message", length = 1024) private String errorMessage;
    @Column(nullable = false) private boolean retryable;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;

    protected DocumentAgentJobEntity() {}

    public DocumentAgentJobEntity(DocumentAgentSessionEntity session, int sequenceNo, String idempotencyKey, String instruction) {
        this.jobKey = UUID.randomUUID().toString(); this.session = session; this.sequenceNo = sequenceNo;
        this.idempotencyKey = idempotencyKey; this.instruction = instruction; this.status = "QUEUED";
    }
    public void dispatching() { this.status = "DISPATCHING"; }
    public void running(String runtimeJobId) { this.status = "RUNNING"; this.runtimeJobId = runtimeJobId; this.startedAt = Instant.now(); }
    public void complete() { this.status = "COMPLETED"; this.finishedAt = Instant.now(); }
    public void cancel() { this.status = "CANCELLED"; this.finishedAt = Instant.now(); }
    public void fail(String code, String message, boolean retryable) { this.status = "FAILED"; this.errorCode = code; this.errorMessage = message; this.retryable = retryable; this.finishedAt = Instant.now(); }
    public String getJobKey() { return jobKey; }
    public DocumentAgentSessionEntity getSession() { return session; }
    public int getSequenceNo() { return sequenceNo; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getInstruction() { return instruction; }
    public String getStatus() { return status; }
    public String getRuntimeJobId() { return runtimeJobId; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isRetryable() { return retryable; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getVersionNo() { return versionNo; }
}
