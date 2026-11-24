package com.company.skillplatform.knowledge.interfaces;

import com.company.skillplatform.knowledge.application.KnowledgeRoleMetadataReconcileService;
import com.company.skillplatform.knowledge.application.KnowledgeRoleMetadataSync;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/knowledge")
public class KnowledgeRoleMetadataController {
    private final KnowledgeRoleMetadataReconcileService reconcile;
    private final KnowledgeRoleMetadataSync sync;

    public KnowledgeRoleMetadataController(KnowledgeRoleMetadataReconcileService reconcile, KnowledgeRoleMetadataSync sync) {
        this.reconcile=reconcile;
        this.sync=sync;
    }

    @PostMapping("/role-metadata:backfill")
    @PreAuthorize("hasAuthority('admin:identity')")
    public ResponseEntity<Map<String, Long>> backfill(Authentication actor) {
        return ResponseEntity.accepted().body(Map.of("jobId",reconcile.start((Long)actor.getPrincipal())));
    }

    @GetMapping("/role-metadata/status")
    @PreAuthorize("hasAnyAuthority('admin:identity','admin:audit')")
    public Map<String,Object> status() { return reconcile.status(); }

    @PostMapping("/role-metadata:retry")
    @PreAuthorize("hasAuthority('admin:identity')")
    public Map<String,Integer> retry() { return Map.of("retried",sync.retry()); }
}
