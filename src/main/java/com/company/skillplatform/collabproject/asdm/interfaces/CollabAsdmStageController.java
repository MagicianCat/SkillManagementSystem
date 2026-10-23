package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.asdm.application.CollabAsdmMultiStageDetectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read and manage ASDM discovery for each requirement stage. */
@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}/asdm/stages")
public class CollabAsdmStageController {
    private final CollabAsdmMultiStageDetectionService detection;

    public CollabAsdmStageController(CollabAsdmMultiStageDetectionService detection) {
        this.detection = detection;
    }

    @GetMapping
    public CollabAsdmMultiStageDetectionService.StageList stages(@PathVariable String requirementKey, Authentication auth) {
        return detection.stages(requirementKey, actor(auth));
    }

    @GetMapping("/{stageKey}")
    public CollabAsdmMultiStageDetectionService.StageDetail stage(@PathVariable String requirementKey,
            @PathVariable String stageKey, Authentication auth) {
        return detection.stage(requirementKey, stageKey, actor(auth));
    }

    @GetMapping("/{stageKey}/candidate")
    public CollabAsdmMultiStageDetectionService.CandidateDetail candidate(@PathVariable String requirementKey,
            @PathVariable String stageKey, Authentication auth) {
        return detection.candidate(requirementKey, stageKey, actor(auth));
    }

    @PostMapping("/{stageKey}:scan")
    public CollabAsdmMultiStageDetectionService.ScanQueued scan(@PathVariable String requirementKey,
            @PathVariable String stageKey, Authentication auth) {
        return detection.scanNow(requirementKey, stageKey, actor(auth));
    }

    @PutMapping("/{stageKey}/associations")
    public CollabAsdmMultiStageDetectionService.CandidateDetail associations(@PathVariable String requirementKey,
            @PathVariable String stageKey, @Valid @RequestBody AssociationRequest request, Authentication auth) {
        var changes = request.changes().stream().map(c -> new CollabAsdmMultiStageDetectionService.AssociationChange(c.path(),c.association())).toList();
        return detection.updateAssociations(requirementKey, stageKey,
                new CollabAsdmMultiStageDetectionService.AssociationUpdate(request.expectedRevision(),request.expectedVersion(),changes), actor(auth));
    }

    @PostMapping("/{stageKey}/candidate:include-suspected")
    public CollabAsdmMultiStageDetectionService.AssociationBatchResult includeSuspected(@PathVariable String requirementKey,
            @PathVariable String stageKey, @Valid @RequestBody IncludeSuspectedRequest request, Authentication auth) {
        return detection.includeSuspectedFiles(requirementKey,stageKey,request.candidateId(),request.expectedRevision(),actor(auth));
    }

    private long actor(Authentication auth) { return ((Number) auth.getPrincipal()).longValue(); }

    public record AssociationRequest(@Positive int expectedRevision,@Min(0) int expectedVersion,@NotEmpty List<@Valid AssociationChange> changes) {}
    public record IncludeSuspectedRequest(@Positive long candidateId,@Positive int expectedRevision) {}
    public record AssociationChange(@NotBlank String path, @NotNull String association) {}
}
