package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.asdm.application.CollabAsdmSyncService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}/asdm")
public class CollabAsdmSyncController {
    private final CollabAsdmSyncService sync;
    public CollabAsdmSyncController(CollabAsdmSyncService sync) {this.sync=sync;}

    @PostMapping("/sync")
    public CollabAsdmSyncService.SyncAccepted start(@PathVariable String requirementKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CollabAsdmSyncService.SyncRequest request,Authentication authentication) {
        return sync.start(requirementKey,actor(authentication),idempotencyKey,request);
    }

    @GetMapping("/sync-jobs/{jobId}")
    public CollabAsdmSyncService.JobView job(@PathVariable String requirementKey,@PathVariable long jobId,
            Authentication authentication) {return sync.job(requirementKey,actor(authentication),jobId);}

    @PostMapping("/sync-jobs/{jobId}/retry")
    public CollabAsdmSyncService.JobView retry(@PathVariable String requirementKey,@PathVariable long jobId,
            Authentication authentication) {return sync.retry(requirementKey,actor(authentication),jobId);}

    private long actor(Authentication authentication){return ((Number)authentication.getPrincipal()).longValue();}
}
