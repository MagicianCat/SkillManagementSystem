package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_session")
public class AgentSessionEntity extends BaseJpaEntity {
    @Column(name="session_key", nullable=false, unique=true, columnDefinition="char(36)") private String sessionKey;
    @Column(name="owner_user_id", nullable=false) private Long ownerUserId;
    @Column(name="profile_key", nullable=false, length=64) private String profileKey;
    @Column(length=255) private String title;
    @Column(nullable=false, length=32) private String status;
    @Column(length=64) private String platform;
    @Column(name="os_type", length=32) private String osType;
    @Column(name="last_message_at") private Instant lastMessageAt;
    @Version @Column(name="version_no", nullable=false) private int versionNo;

    protected AgentSessionEntity() {}
    public AgentSessionEntity(Long ownerUserId, String profileKey, String platform, String osType) {
        this.sessionKey = UUID.randomUUID().toString(); this.ownerUserId = ownerUserId;
        this.profileKey = profileKey; this.platform = platform; this.osType = osType; this.status = "ACTIVE";
    }
    public void touch(String firstMessage) { if (title == null) title = firstMessage.substring(0, Math.min(40, firstMessage.length())); lastMessageAt = Instant.now(); }
    public void close() { status = "CLOSED"; }
    public String getSessionKey(){return sessionKey;} public Long getOwnerUserId(){return ownerUserId;} public String getProfileKey(){return profileKey;}
    public String getTitle(){return title;} public String getStatus(){return status;} public String getPlatform(){return platform;} public String getOsType(){return osType;}
    public Instant getLastMessageAt(){return lastMessageAt;} public int getVersionNo(){return versionNo;}
}
