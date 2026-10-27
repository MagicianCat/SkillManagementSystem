package com.company.skillplatform.knowledge.interfaces;

import com.company.skillplatform.knowledge.application.KnowledgeReconcileService;
import com.company.skillplatform.knowledge.application.KnowledgeReconcileAsyncWorker;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class KnowledgeAdminController {
    private final KnowledgeReconcileService reconcile;
    private final KnowledgeReconcileAsyncWorker worker;
    public KnowledgeAdminController(KnowledgeReconcileService reconcile, KnowledgeReconcileAsyncWorker worker){this.reconcile=reconcile;this.worker=worker;}
    @PreAuthorize("hasAuthority('admin:identity') or hasAuthority('admin:audit')")
    @GetMapping("/status") public KnowledgeReconcileService.ReconcileStatus status(){return reconcile.status();}
    @PreAuthorize("hasAuthority('admin:identity')")
    @PostMapping("/reconcile") public ResponseEntity<ReconcileSubmission> reconcile(Authentication authentication){
        Long actorId = (Long) authentication.getPrincipal();
        if (!reconcile.reserveManualReconcile()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ReconcileSubmission("ALREADY_RUNNING", "已有知识库重建任务正在运行"));
        }
        try {
            worker.execute(actorId);
            return ResponseEntity.accepted().body(new ReconcileSubmission("ACCEPTED", "知识库向量强制重建任务已提交，完成后会发送站内通知"));
        } catch (TaskRejectedException rejected) {
            reconcile.releaseManualReconcile();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ReconcileSubmission("QUEUE_FULL", "后台任务繁忙，请稍后重试"));
        }
    }
    public record ReconcileSubmission(String status, String message) {}
}
