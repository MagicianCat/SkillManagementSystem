package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "feishu_bot_binding", uniqueConstraints = @UniqueConstraint(name = "uk_feishu_bot_binding_scope", columnNames = {"owner_user_id", "scope_key"}))
public class FeishuBotBindingEntity extends BaseJpaEntity {
    @Column(name = "owner_user_id", nullable = false) private Long ownerUserId;
    @Column(name = "scope_key", nullable = false, length = 255) private String scopeKey;
    @Column(name = "chat_id", nullable = false, length = 128) private String chatId;
    @Column(name = "chat_type", nullable = false, length = 32) private String chatType;
    @Column(name = "thread_key", nullable = false, length = 255) private String threadKey;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "device_type", nullable = false, length = 32) private String deviceType;

    protected FeishuBotBindingEntity() {}
    public FeishuBotBindingEntity(Long ownerUserId, String scopeKey, String chatId, String chatType, String threadKey, Long sessionId, String deviceType) {
        this.ownerUserId = ownerUserId; this.scopeKey = scopeKey; this.chatId = chatId; this.chatType = chatType;
        this.threadKey = threadKey; this.sessionId = sessionId; this.status = "ACTIVE"; this.deviceType = deviceType;
    }
    public void replaceSession(Long sessionId, String deviceType) { this.sessionId = sessionId; this.deviceType = deviceType; this.status = "ACTIVE"; }
    public void touch(String deviceType) { this.deviceType = deviceType; }
    public Long getOwnerUserId(){return ownerUserId;} public String getScopeKey(){return scopeKey;} public String getChatId(){return chatId;}
    public String getChatType(){return chatType;} public String getThreadKey(){return threadKey;} public Long getSessionId(){return sessionId;}
    public String getStatus(){return status;} public String getDeviceType(){return deviceType;}
}
