package com.company.skillplatform.collabproject.progress.interfaces;

import com.company.skillplatform.collabproject.progress.application.CollabProgressService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}")
public class CollabProgressController {
    private final CollabProgressService service;
    public CollabProgressController(CollabProgressService service) { this.service = service; }

    @GetMapping("/progress-document")
    public CollabProgressService.ProgressView get(@PathVariable String requirementKey, Authentication auth) {
        return service.get(requirementKey, ((Number) auth.getPrincipal()).longValue());
    }

    @PostMapping("/progress-document:refresh")
    public CollabProgressService.ProgressView refresh(@PathVariable String requirementKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey, Authentication auth) {
        return service.refresh(requirementKey, ((Number) auth.getPrincipal()).longValue(), idempotencyKey);
    }

    @PostMapping("/progress-document:reconcile")
    public CollabProgressService.ProgressView reconcile(@PathVariable String requirementKey,
            @RequestBody(required = false) ReconcileRequest request, Authentication auth) {
        return service.reconcile(requirementKey, ((Number) auth.getPrincipal()).longValue(), request != null && request.confirmMissing());
    }
    public record ReconcileRequest(boolean confirmMissing) {}
}
