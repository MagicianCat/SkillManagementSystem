package com.company.skillplatform.user.application;

import com.company.skillplatform.notification.application.NotificationService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class DefaultFeishuRoleBackfillJob {
    private static final Logger log = LoggerFactory.getLogger(DefaultFeishuRoleBackfillJob.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final DefaultFeishuRoleService roles;
    private final NotificationService notifications;

    public DefaultFeishuRoleBackfillJob(DefaultFeishuRoleService roles, NotificationService notifications) {
        this.roles = roles;
        this.notifications = notifications;
    }

    public boolean reserve() { return running.compareAndSet(false, true); }
    public void release() { running.set(false); }

    @Async
    public void execute(Long actorId) {
        try {
            int granted = roles.grantToUnassignedUsers();
            log.info("event=feishu.default_role.backfill.completed actorId={} granted={}", actorId, granted);
            notifyActor(actorId, true, "已为 " + granted + " 位没有平台权限的飞书成员分配普通用户权限。");
        } catch (RuntimeException failure) {
            log.error("event=feishu.default_role.backfill.failed actorId={}", actorId, failure);
            notifyActor(actorId, false, "权限补齐失败：" + failure.getClass().getSimpleName());
        } finally {
            release();
        }
    }

    private void notifyActor(Long actorId, boolean succeeded, String content) {
        try { notifications.defaultRoleBackfillCompleted(actorId, succeeded, content); }
        catch (RuntimeException failure) { log.error("event=feishu.default_role.backfill.notification_failed actorId={}", actorId, failure); }
    }
}
