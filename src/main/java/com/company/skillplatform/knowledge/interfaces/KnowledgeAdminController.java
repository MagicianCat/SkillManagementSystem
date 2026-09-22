package com.company.skillplatform.knowledge.interfaces;

import com.company.skillplatform.knowledge.application.KnowledgeReconcileService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
@PreAuthorize("hasAuthority('admin:identity') or hasAuthority('admin:audit')")
public class KnowledgeAdminController {
    private final KnowledgeReconcileService reconcile;
    public KnowledgeAdminController(KnowledgeReconcileService reconcile){this.reconcile=reconcile;}
    @GetMapping("/status") public KnowledgeReconcileService.ReconcileStatus status(){return reconcile.status();}
    @PostMapping("/reconcile") public KnowledgeReconcileService.ReconcileResult reconcile(){return reconcile.reconcile();}
}
