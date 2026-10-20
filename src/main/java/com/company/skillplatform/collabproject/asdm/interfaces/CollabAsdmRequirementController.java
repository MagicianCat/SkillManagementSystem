package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.asdm.application.CollabAsdmDetectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}")
public class CollabAsdmRequirementController {
    private final CollabAsdmDetectionService detection;

    public CollabAsdmRequirementController(CollabAsdmDetectionService detection) {
        this.detection = detection;
    }

    @GetMapping("/asdm")
    public CollabAsdmDetectionService.AsdmRequirementView status(@PathVariable String requirementKey, Authentication auth) {
        return detection.view(requirementKey, actor(auth));
    }

    @PostMapping("/asdm:scan-now")
    public CollabAsdmDetectionService.ScanAccepted scanNow(@PathVariable String requirementKey, Authentication auth) {
        return detection.scanNow(requirementKey, actor(auth));
    }

    @GetMapping("/asdm/directories:candidates")
    public CollabAsdmDetectionService.DirectoryCandidates directories(@PathVariable String requirementKey, Authentication auth) {
        return detection.directoryCandidates(requirementKey, actor(auth));
    }

    @PostMapping("/asdm/directory:bind")
    public CollabAsdmDetectionService.AsdmRequirementView bind(@PathVariable String requirementKey,
            @Valid @RequestBody BindDirectoryRequest request, Authentication auth) {
        return detection.bindDirectory(requirementKey, request.path(), request.expectedVersion(), actor(auth));
    }

    private long actor(Authentication auth) { return ((Number) auth.getPrincipal()).longValue(); }

    public record BindDirectoryRequest(@NotBlank String path, @NotNull @PositiveOrZero Integer expectedVersion) {}
}
