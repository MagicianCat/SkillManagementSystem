package com.company.skillplatform.knowledge.application;

import com.company.skillplatform.notification.application.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeReconcileAsyncWorker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReconcileAsyncWorker.class);

    private final KnowledgeReconcileService reconcile;
    private final NotificationService notifications;

    public KnowledgeReconcileAsyncWorker(KnowledgeReconcileService reconcile, NotificationService notifications) {
        this.reconcile = reconcile;
        this.notifications = notifications;
    }

    @Async("knowledgeIndexExecutor")
    public void execute(Long actorId) {
        try {
            KnowledgeReconcileService.ReconcileResult result = reconcile.manualReconcile();
            boolean succeeded = result.failed() == 0 && !result.providerQuotaExhausted();
            String content = "知识库向量强制重建" + (succeeded ? "完成" : "结束但有失败")
                    + "：扫描 " + result.activeDocuments() + " 篇，重建 " + result.rebuilt()
                    + " 篇，清理 " + result.deleted() + " 篇，失败 " + result.failed() + " 篇。"
                    + (result.providerQuotaExhausted() ? "Embedding 额度耗尽，任务已停止。" : "");
            notifyActor(actorId, succeeded, content);
        } catch (RuntimeException failure) {
            log.error("event=knowledge.manual_reconcile.failed actorId={} errorType={}",
                    actorId, failure.getClass().getSimpleName(), failure);
            notifyActor(actorId, false, "知识库向量强制重建失败：" + safeMessage(failure));
        } finally {
            reconcile.releaseManualReconcile();
        }
    }

    private void notifyActor(Long actorId, boolean succeeded, String content) {
        try {
            notifications.wikiIndexRebuildCompleted(actorId, succeeded, content);
        } catch (RuntimeException notificationFailure) {
            log.error("event=knowledge.manual_reconcile.notification_failed actorId={} errorType={}",
                    actorId, notificationFailure.getClass().getSimpleName(), notificationFailure);
        }
    }

    private String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
