package com.company.skillplatform.notification.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.notification.domain.NotificationType;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_notification")
public class UserNotificationEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "recipient_id") private IamUserEntity recipient;
    @Enumerated(EnumType.STRING) @Column(name = "notification_type", nullable = false, length = 64) private NotificationType type;
    @Column(nullable = false, length = 256) private String title;
    @Column(nullable = false, length = 2048) private String content;
    @Column(name = "target_type", nullable = false, length = 64) private String targetType;
    @Column(name = "target_id", nullable = false) private Long targetId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "skill_id") private SkillEntity skill;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "skill_version_id") private SkillVersionEntity version;
    @Column(name = "read_at") private Instant readAt;

    protected UserNotificationEntity() {}

    public UserNotificationEntity(IamUserEntity recipient, NotificationType type, String title, String content,
                                  String targetType, Long targetId, SkillVersionEntity version) {
        this.recipient = recipient; this.type = type; this.title = title; this.content = content;
        this.targetType = targetType; this.targetId = targetId; this.version = version;
        this.skill = version == null ? null : version.getSkill();
    }

    public void markRead(Instant now) { if (readAt == null) readAt = now; }
    public IamUserEntity getRecipient() { return recipient; }
    public NotificationType getType() { return type; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public String getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public SkillEntity getSkill() { return skill; }
    public SkillVersionEntity getVersion() { return version; }
    public Instant getReadAt() { return readAt; }
}

