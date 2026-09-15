package com.company.skillplatform.telemetry.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "skill_usage_conversation")
public class SkillUsageConversationEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @Column(name = "client_session_id", nullable = false, length = 256) private String clientSessionId;
    @Column(name = "current_object_key", length = 512) private String currentObjectKey;
    @Column(name = "conversation_version", nullable = false) private long conversationVersion;
    @Column(name = "message_count", nullable = false) private int messageCount;
    @Column(name = "last_sampled_at") private Instant lastSampledAt;
    @Column(name = "offline_persisted_version") private Long offlinePersistedVersion;
    @Column(name = "offline_persisted_at") private Instant offlinePersistedAt;
    @Column(nullable = false, length = 32) private String status;
    protected SkillUsageConversationEntity() {}
    public SkillUsageConversationEntity(IamUserEntity user, String clientSessionId, Instant now) { this.user = user; this.clientSessionId = clientSessionId; this.lastSampledAt = now; this.status = "ACTIVE"; }
    public String getClientSessionId() { return clientSessionId; }
    public String getCurrentObjectKey() { return currentObjectKey; }
    public String getStatus() { return status; }
    public long getConversationVersion() { return conversationVersion; }
    public void merged(String objectKey, int messageCount, Instant now) { this.currentObjectKey = objectKey; this.messageCount = messageCount; this.conversationVersion++; this.lastSampledAt = now; this.offlinePersistedVersion = null; this.offlinePersistedAt = null; }
    public void markPurged() { currentObjectKey = null; status = "PURGED"; }
}
