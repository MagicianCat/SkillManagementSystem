package com.company.skillplatform.notification.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.logging.LogContext;
import com.company.skillplatform.notification.domain.NotificationType;
import com.company.skillplatform.notification.infrastructure.entity.UserNotificationEntity;
import com.company.skillplatform.notification.infrastructure.repository.UserNotificationRepository;
import com.company.skillplatform.skill.infrastructure.repository.SkillOwnerRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRoleRepository;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NotificationService.class);
    private final UserNotificationRepository notifications;
    private final IamUserRoleRepository userRoles;
    private final IamUserRepository users;
    private final Clock clock;
    private SkillOwnerRepository owners;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationService(UserNotificationRepository notifications, IamUserRoleRepository userRoles,
                               IamUserRepository users) {
        this(notifications, userRoles, users, Clock.systemUTC());
    }

    NotificationService(UserNotificationRepository notifications, IamUserRoleRepository userRoles,
                        IamUserRepository users, Clock clock) {
        this.notifications = notifications; this.userRoles = userRoles; this.users = users; this.clock = clock;
    }
    @org.springframework.beans.factory.annotation.Autowired public void setOwners(SkillOwnerRepository owners) { this.owners = owners; }

    @Transactional
    public void reviewSubmitted(SkillVersionEntity version, Long reviewId) {
        save(userRoles.findActiveUsersByPermission("skill:review"), NotificationType.REVIEW_SUBMITTED,
                "新的 Skill 审核任务", version.getSkill().getDisplayName() + " 已提交审核",
                "REVIEW", reviewId, version);
    }

    @Transactional
    public void publishCompleted(SkillVersionEntity version, Long taskId, Long publisherId, boolean succeeded, String errorCode) {
        LinkedHashMap<Long,IamUserEntity> recipients = ownerRecipients(version);
        NotificationType type = succeeded ? NotificationType.PUBLISH_SUCCEEDED : NotificationType.PUBLISH_FAILED;
        String result = succeeded ? "发布成功" : "发布失败";
        String content = version.getSkill().getDisplayName() + " " + result + (errorCode == null ? "" : "：" + errorCode);
        users.findById(publisherId).ifPresent(publisher -> save(java.util.List.of(publisher), type, "Skill " + result, content, "BUILD_TASK", taskId, version));
        recipients.remove(publisherId);
        save(recipients.values(), type, "Skill " + result, content, "SKILL_VERSION", version.getId(), version);
    }

    @Transactional
    public void lifecycleChanged(SkillVersionEntity version, String event) {
        NotificationType type = NotificationType.valueOf(event);
        String action = type == NotificationType.SKILL_DEPRECATED ? "已废弃" : "已下架";
        save(ownerRecipients(version).values(), type, "Skill " + action,
                version.getSkill().getDisplayName() + " " + action, "SKILL_VERSION", version.getId(), version);
    }

    @Transactional
    public void reviewCompleted(SkillVersionEntity version, Long reviewId, boolean approved, Long submitterId, String comment) {
        IamUserEntity submitter = users.findById(submitterId).orElseThrow(() -> new BusinessException(
                "USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        NotificationType type = approved ? NotificationType.REVIEW_APPROVED : NotificationType.REVIEW_REJECTED;
        String result = approved ? "审核通过" : "审核拒绝";
        save(java.util.List.of(submitter), type, "Skill " + result,
                version.getSkill().getDisplayName() + " " + result + "：" + comment,
                "SKILL_VERSION", version.getId(), version);
    }

    /** Reserved orchestration hook; call after publish when version-update recommendation is enabled. */
    @Transactional
    public void versionUpdated(SkillVersionEntity version, VersionUpdateRecipientProvider provider) {
        save(provider.recipientsFor(version), NotificationType.SKILL_VERSION_UPDATED,
                "Skill 有新版本", version.getSkill().getDisplayName() + " 已发布 " + version.getVersion(),
                "SKILL_VERSION", version.getId(), version);
    }

    @PreAuthorize("isAuthenticated()") @Transactional(readOnly = true)
    public Page<NotificationView> inbox(Long userId, boolean unreadOnly, Pageable pageable) {
        Page<UserNotificationEntity> page = unreadOnly
                ? notifications.findByRecipientIdAndReadAtIsNull(userId, pageable)
                : notifications.findByRecipientId(userId, pageable);
        return page.map(NotificationService::view);
    }

    @PreAuthorize("isAuthenticated()") @Transactional(readOnly = true)
    public long unreadCount(Long userId) { return notifications.countByRecipientIdAndReadAtIsNull(userId); }

    @PreAuthorize("isAuthenticated()") @Transactional
    public NotificationView markRead(Long id, Long userId) {
        UserNotificationEntity entity = notifications.findByIdAndRecipientId(id, userId).orElseThrow(() ->
                new BusinessException("NOTIFICATION_NOT_FOUND", "Notification not found", HttpStatus.NOT_FOUND));
        entity.markRead(clock.instant());
        log.info("event=notification.read requestId={} actorId={} notificationId={} type={}",
                LogContext.requestId(), userId, id, entity.getType());
        return view(entity);
    }

    private void save(Collection<IamUserEntity> recipients, NotificationType type, String title, String content,
                      String targetType, Long targetId, SkillVersionEntity version) {
        if (recipients.isEmpty()) {
            log.info("event=notification.skipped requestId={} type={} targetType={} targetId={} skillKey={} versionId={} reason=no_recipients",
                    LogContext.requestId(), type, targetType, targetId,
                    version == null ? null : version.getSkill().getSkillKey(), version == null ? null : version.getId());
            return;
        }
        notifications.saveAll(recipients.stream().map(user -> new UserNotificationEntity(
                user, type, title, content, targetType, targetId, version)).toList());
        log.info("event=notification.created requestId={} type={} targetType={} targetId={} skillKey={} versionId={} recipientCount={}",
                LogContext.requestId(), type, targetType, targetId,
                version == null ? null : version.getSkill().getSkillKey(), version == null ? null : version.getId(), recipients.size());
    }

    private LinkedHashMap<Long,IamUserEntity> ownerRecipients(SkillVersionEntity version) {
        LinkedHashMap<Long,IamUserEntity> result = new LinkedHashMap<>();
        if (owners != null) owners.findBySkillId(version.getSkill().getId()).forEach(owner -> result.put(owner.getUser().getId(), owner.getUser()));
        return result;
    }

    private static NotificationView view(UserNotificationEntity n) {
        return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getTargetType(),
                n.getTargetId(), n.getSkill() == null ? null : n.getSkill().getSkillKey(),
                n.getVersion() == null ? null : n.getVersion().getId(), n.getReadAt(), n.getTimeCreated());
    }

    public record NotificationView(Long id, NotificationType type, String title, String content, String targetType,
                                   Long targetId, String skillKey, Long versionId, Instant readAt, Instant createdAt) {}
}
