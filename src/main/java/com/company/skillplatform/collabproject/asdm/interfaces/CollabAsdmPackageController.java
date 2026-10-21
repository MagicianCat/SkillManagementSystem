package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.asdm.publication.CollabAsdmPackageRetryService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read per-file Feishu publication progress and retry failed package publications. */
@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}/asdm")
public class CollabAsdmPackageController {
    private final CollabAsdmPackageRetryService packages;

    public CollabAsdmPackageController(CollabAsdmPackageRetryService packages) {
        this.packages = packages;
    }

    @GetMapping("/stages/{stageKey}/packages/{packageId}")
    public CollabAsdmPackageRetryService.PackageStatus status(@PathVariable String requirementKey,
            @PathVariable String stageKey, @PathVariable long packageId, Authentication auth) {
        return packages.status(requirementKey, stageKey, packageId, actor(auth));
    }

    @PostMapping("/packages/{packageId}:retry")
    public CollabAsdmPackageRetryService.RetryResult retry(@PathVariable String requirementKey,
            @PathVariable long packageId, Authentication auth) {
        return packages.retry(requirementKey, packageId, actor(auth));
    }

    private long actor(Authentication auth) {
        return ((Number) auth.getPrincipal()).longValue();
    }
}
