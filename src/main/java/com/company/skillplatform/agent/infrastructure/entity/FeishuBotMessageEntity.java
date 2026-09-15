package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "feishu_bot_message", uniqueConstraints = @UniqueConstraint(name = "uk_feishu_bot_message_id", columnNames = "message_id"))
public class FeishuBotMessageEntity extends BaseJpaEntity {
    @Column(name = "message_id", nullable = false, length = 128) private String messageId;
    @Column(name = "event_request_id", length = 128) private String eventRequestId;
    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "chat_id", nullable = false, length = 128) private String chatId;
    @Column(name = "chat_type", nullable = false, length = 32) private String chatType;
    @Column(name = "thread_key", nullable = false, length = 255) private String threadKey;
    @Lob @Column(nullable = false, columnDefinition = "mediumtext") private String content;
    @Column(name = "user_agent", length = 512) private String userAgent;
    @Column(name = "device_type", nullable = false, length = 32) private String deviceType;
    @Column(name = "binding_id") private Long bindingId;
    @Column(name = "agent_session_key", length = 36, columnDefinition = "char(36)") private String agentSessionKey;
    @Column(name = "agent_run_key", length = 36, columnDefinition = "char(36)") private String agentRunKey;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "retry_count", nullable = false) private int retryCount;
    @Column(name = "reply_message_id", length = 128) private String replyMessageId;
    @Column(name = "next_retry_at") private Instant nextRetryAt;

    protected FeishuBotMessageEntity() {}
    public FeishuBotMessageEntity(String messageId, String eventRequestId, Long ownerUserId, String chatId, String chatType,
                                  String threadKey, String content, String userAgent, String deviceType) {
        this.messageId=messageId; this.eventRequestId=eventRequestId; this.ownerUserId=ownerUserId; this.chatId=chatId;
        this.chatType=chatType; this.threadKey=threadKey; this.content=content; this.userAgent=userAgent; this.deviceType=deviceType;
        this.status="RECEIVED"; this.retryCount=0;
    }
    public void bound(Long bindingId, String sessionKey, String runKey){this.bindingId=bindingId;this.agentSessionKey=sessionKey;this.agentRunKey=runKey;this.status="RUN_SUBMITTED";}
    public void status(String value){this.status=value;}
    public void replied(String replyId){this.replyMessageId=replyId;this.status="REPLIED";this.nextRetryAt=null;}
    public void failed(String code, Instant retryAt){this.errorCode=code;this.retryCount++;this.nextRetryAt=retryAt;this.status=retryAt==null?"FAILED":"RETRY_WAITING";}
    public String getMessageId(){return messageId;} public Long getOwnerUserId(){return ownerUserId;} public String getChatId(){return chatId;}
    public String getChatType(){return chatType;} public String getThreadKey(){return threadKey;} public String getContent(){return content;}
    public String getUserAgent(){return userAgent;} public String getDeviceType(){return deviceType;} public Long getBindingId(){return bindingId;}
    public String getAgentSessionKey(){return agentSessionKey;} public String getAgentRunKey(){return agentRunKey;} public String getStatus(){return status;}
    public String getErrorCode(){return errorCode;} public int getRetryCount(){return retryCount;} public String getReplyMessageId(){return replyMessageId;}
    public Instant getNextRetryAt(){return nextRetryAt;}
}
