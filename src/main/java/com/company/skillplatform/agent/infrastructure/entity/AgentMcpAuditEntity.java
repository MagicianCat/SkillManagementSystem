package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agent_mcp_audit")
public class AgentMcpAuditEntity extends BaseJpaEntity {
    @Column(name = "run_key", nullable = false, length = 36, columnDefinition = "char(36)") private String runKey;
    @Column(name = "session_key", nullable = false, length = 36, columnDefinition = "char(36)") private String sessionKey;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "actor_user_id") private IamUserEntity actor;
    @Column(name = "tool_name", nullable = false, length = 128) private String toolName;
    @Column(name = "call_id", nullable = false, length = 128) private String callId;
    @Column(name = "source_channel", nullable = false, length = 32) private String sourceChannel;
    @Column(name = "knowledge_scope", nullable = false, length = 64) private String knowledgeScope;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "error_code", length = 128) private String errorCode;
    @Column(name = "arguments_summary", length = 2000) private String argumentsSummary;

    protected AgentMcpAuditEntity() {}

    public AgentMcpAuditEntity(String runKey, String sessionKey, IamUserEntity actor, String toolName,
                                String callId, String sourceChannel, String knowledgeScope,
                                String argumentsSummary, Instant startedAt) {
        this.runKey = runKey; this.sessionKey = sessionKey; this.actor = actor;
        this.toolName = toolName; this.callId = callId; this.sourceChannel = sourceChannel;
        this.knowledgeScope = knowledgeScope; this.argumentsSummary = argumentsSummary;
        this.startedAt = startedAt; this.status = "STARTED";
    }

    public void succeeded(Instant finishedAt) { finish("SUCCEEDED", finishedAt, null); }
    public void failed(Instant finishedAt, String errorCode) { this.errorCode = limit(errorCode, 128); finish("FAILED", finishedAt, this.errorCode); }
    private void finish(String status, Instant finishedAt, String ignored) {
        this.status = status; this.finishedAt = finishedAt;
        this.durationMs = Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
    }
    private static String limit(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }

    public String getRunKey() { return runKey; }
    public String getSessionKey() { return sessionKey; }
    public IamUserEntity getActor() { return actor; }
    public String getToolName() { return toolName; }
    public String getCallId() { return callId; }
    public String getSourceChannel() { return sourceChannel; }
    public String getKnowledgeScope() { return knowledgeScope; }
    public String getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getDurationMs() { return durationMs; }
    public String getErrorCode() { return errorCode; }
    public String getArgumentsSummary() { return argumentsSummary; }
}
