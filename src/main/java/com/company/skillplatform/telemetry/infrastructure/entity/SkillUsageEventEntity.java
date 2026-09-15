package com.company.skillplatform.telemetry.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "skill_usage_event")
public class SkillUsageEventEntity extends BaseJpaEntity {
    @Column(name = "event_uuid", nullable = false, unique = true, length = 64) private String eventUuid;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "skill_id", nullable = false) private SkillEntity skill;
    @Column(name = "skill_version_id") private Long skillVersionId;
    @Column(name = "skill_key_snapshot", nullable = false, length = 128) private String skillKeySnapshot;
    @Column(name = "installation_id", length = 64) private String installationId;
    @Lob @Column(name = "feishu_user_id_ciphertext", nullable = false, columnDefinition = "TEXT") private String feishuUserIdCiphertext;
    @Lob @Column(name = "feishu_open_id_ciphertext", nullable = false, columnDefinition = "TEXT") private String feishuOpenIdCiphertext;
    @Column(name = "local_directory", nullable = false, length = 2048) private String localDirectory;
    @Column(name = "client_session_id", nullable = false, length = 256) private String clientSessionId;
    @Column(name = "generation_id", length = 256) private String generationId;
    @Column(nullable = false, length = 64) private String client;
    @Column(name = "client_version", length = 64) private String clientVersion;
    @Column(name = "agent_type", length = 64) private String agentType;
    @Column(length = 256) private String model;
    @Column(name = "invoked_at", nullable = false) private Instant invokedAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "conversation_status", nullable = false, length = 32) private String conversationStatus;
    @Column(name = "conversation_error_code", length = 64) private String conversationErrorCode;
    @Column(name = "conversation_chunk_object_key", length = 512) private String conversationChunkObjectKey;
    @Column(name = "conversation_attempts", nullable = false) private int conversationAttempts;

    protected SkillUsageEventEntity() {}

    public SkillUsageEventEntity(String eventUuid, IamUserEntity user, SkillEntity skill, Long skillVersionId,
            String skillKeySnapshot, String installationId, String feishuUserIdCiphertext,
            String feishuOpenIdCiphertext, String localDirectory, String clientSessionId, String generationId,
            String client, String clientVersion, String agentType, String model, Instant invokedAt, Instant receivedAt) {
        this.eventUuid = eventUuid; this.user = user; this.skill = skill; this.skillVersionId = skillVersionId;
        this.skillKeySnapshot = skillKeySnapshot; this.installationId = installationId;
        this.feishuUserIdCiphertext = feishuUserIdCiphertext; this.feishuOpenIdCiphertext = feishuOpenIdCiphertext;
        this.localDirectory = localDirectory; this.clientSessionId = clientSessionId; this.generationId = generationId;
        this.client = client; this.clientVersion = clientVersion; this.agentType = agentType; this.model = model;
        this.invokedAt = invokedAt; this.receivedAt = receivedAt; this.conversationStatus = "NOT_PROVIDED";
    }

    public String getEventUuid() { return eventUuid; }
    public String getSkillKeySnapshot() { return skillKeySnapshot; }
    public String getConversationStatus() { return conversationStatus; }
    public String getConversationChunkObjectKey() { return conversationChunkObjectKey; }
    public int getConversationAttempts() { return conversationAttempts; }
    public Long getUserId() { return user.getId(); }
    public IamUserEntity getUser() { return user; }
    public String getClientSessionId() { return clientSessionId; }
    public Instant getInvokedAt() { return invokedAt; }
    public void markConversationStatus(String status, String errorCode) { conversationStatus = status; conversationErrorCode = errorCode; }
    public void stageConversation(String objectKey) { conversationChunkObjectKey = objectKey; conversationStatus = "STAGED"; }
    public void incrementConversationAttempts() { conversationAttempts++; }
}
