package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_agent_session")
public class DocumentAgentSessionEntity extends BaseJpaEntity {
    @Column(name = "session_key", nullable = false, unique = true, columnDefinition = "char(36)") private String sessionKey;
    @Column(name = "idempotency_key", nullable = false, length = 255) private String idempotencyKey;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private VirtualProjectEntity project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "owner_user_id", nullable = false) private IamUserEntity owner;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "document_id") private ProjectDocumentEntity document;
    @Column(name = "profile_key", nullable = false, length = 64) private String profileKey;
    @Column(name = "target_title", length = 255) private String targetTitle;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "runtime_session_id", length = 128) private String runtimeSessionId;
    @Column(name = "conversation_id", length = 128) private String conversationId;
    @Column(name = "workspace_id", length = 255) private String workspaceId;
    @Column(name = "mcp_token_hash", nullable = false, length = 64) private String mcpTokenHash;
    @Column(name = "mcp_token_expires_at", nullable = false) private Instant mcpTokenExpiresAt;
    @Column(name = "last_activity_at", nullable = false) private Instant lastActivityAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;

    protected DocumentAgentSessionEntity() {}

    public DocumentAgentSessionEntity(VirtualProjectEntity project, IamUserEntity owner,
                                      ProjectDocumentEntity document, String profileKey,
                                      String targetTitle, String idempotencyKey, String mcpTokenHash, Instant expiresAt,
                                      Instant now) {
        this.sessionKey = UUID.randomUUID().toString();
        this.idempotencyKey = idempotencyKey;
        this.project = project; this.owner = owner; this.document = document;
        this.profileKey = profileKey; this.targetTitle = targetTitle; this.status = "CREATING";
        this.mcpTokenHash = mcpTokenHash; this.mcpTokenExpiresAt = expiresAt; this.lastActivityAt = now;
    }

    public void runtimeBound(String runtimeSessionId, String conversationId, String workspaceId) {
        this.runtimeSessionId = runtimeSessionId; this.conversationId = conversationId;
        this.workspaceId = workspaceId; this.status = "ACTIVE"; this.lastActivityAt = Instant.now();
    }
    public void setMcpTokenHash(String value) { this.mcpTokenHash = value; }
    public void bindDocument(ProjectDocumentEntity value) { this.document = value; }
    public void touch(Instant now, Instant expiresAt) { this.lastActivityAt = now; this.mcpTokenExpiresAt = expiresAt; }
    public void close(Instant now) { this.status = "CLOSED"; this.closedAt = now; this.lastActivityAt = now; }
    public void fail() { this.status = "FAILED"; }
    public void expire(Instant now) { this.status = "EXPIRED"; this.closedAt = now; }
    public String getSessionKey() { return sessionKey; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public VirtualProjectEntity getProject() { return project; }
    public IamUserEntity getOwner() { return owner; }
    public ProjectDocumentEntity getDocument() { return document; }
    public String getProfileKey() { return profileKey; }
    public String getTargetTitle() { return targetTitle; }
    public String getStatus() { return status; }
    public String getRuntimeSessionId() { return runtimeSessionId; }
    public String getConversationId() { return conversationId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getMcpTokenHash() { return mcpTokenHash; }
    public Instant getMcpTokenExpiresAt() { return mcpTokenExpiresAt; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public Instant getClosedAt() { return closedAt; }
    public int getVersionNo() { return versionNo; }
}
