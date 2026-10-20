package com.company.skillplatform.collabproject.interfaces;

import com.company.skillplatform.collabproject.application.CollabRequirementService;
import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.ArtifactUpload;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.DecisionRequest;
import com.company.skillplatform.collabproject.flow.interfaces.CollabProjectFlowController.SubmissionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** New semantic route aliases. Existing collab-projects URLs remain supported for saved skill prompts. */
@RestController
@RequestMapping("/api/v1/collab-requirements/{requirementKey}")
public class CollabRequirementController {
    private final CollabRequirementService requirements;
    private final CollabProjectFlowService flow;

    public CollabRequirementController(CollabRequirementService requirements, CollabProjectFlowService flow) {
        this.requirements = requirements;
        this.flow = flow;
    }

    @GetMapping
    public CollabRequirementService.RequirementView detail(@PathVariable String requirementKey, Authentication auth) {
        return requirements.detail(requirementKey, actor(auth));
    }

    @GetMapping("/roles")
    public CollabRequirementService.RolesView roles(@PathVariable String requirementKey, Authentication auth) {
        return requirements.roles(requirementKey, actor(auth));
    }

    @PutMapping("/stages/{stageKey}/actors")
    public CollabRequirementService.StageActorsView replaceActors(@PathVariable String requirementKey,
            @PathVariable String stageKey, @Valid @RequestBody ActorRequest request, Authentication auth) {
        return requirements.replaceActors(requirementKey, stageKey, request.duty(), request.roleKey(),
                request.userIds(), request.expectedStageVersion(), actor(auth));
    }

    @GetMapping("/stages/{stageKey}/sync-preview")
    public CollabRequirementService.SyncPreview syncPreview(@PathVariable String requirementKey,
            @PathVariable String stageKey, Authentication auth) {
        return requirements.syncPreview(requirementKey, stageKey, actor(auth));
    }

    @PostMapping("/stages/{stageKey}:sync-members")
    public CollabRequirementService.SyncResult syncMembers(@PathVariable String requirementKey,
            @PathVariable String stageKey, @Valid @RequestBody CollabRequirementService.SyncRequest request,
            Authentication auth) {
        return requirements.syncMembers(requirementKey, stageKey, request, actor(auth));
    }

    @GetMapping("/workflow")
    public CollabProjectFlowService.WorkflowView workflow(@PathVariable String requirementKey, Authentication auth) {
        return flow.workflow(requirementKey, actor(auth));
    }

    @PostMapping("/stages/{stageKey}/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public CollabProjectFlowService.ArtifactView upload(@PathVariable String requirementKey,
            @PathVariable String stageKey, @Valid @RequestBody ArtifactUpload request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey, Authentication auth) {
        return flow.upload(requirementKey, stageKey, request, idempotencyKey, actor(auth));
    }

    @PostMapping("/stages/{stageKey}/submissions")
    public CollabProjectFlowService.SubmissionView submit(@PathVariable String requirementKey,
            @PathVariable String stageKey, @Valid @RequestBody SubmissionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey, Authentication auth) {
        return flow.submit(requirementKey, stageKey, request, idempotencyKey, actor(auth));
    }

    @PostMapping("/submissions/{submissionId}/decision")
    public CollabProjectFlowService.SubmissionView decide(@PathVariable String requirementKey,
            @PathVariable Long submissionId, @Valid @RequestBody DecisionRequest request, Authentication auth) {
        return flow.decide(requirementKey, submissionId, request, actor(auth));
    }

    @GetMapping("/context")
    public CollabProjectFlowService.ContextView context(@PathVariable String requirementKey,
            @RequestParam String stageKey, @RequestParam(defaultValue = "false") boolean includeHistory,
            Authentication auth) {
        return flow.context(requirementKey, stageKey, includeHistory, actor(auth));
    }

    @GetMapping("/artifacts")
    public List<CollabProjectFlowService.ArtifactView> artifacts(@PathVariable String requirementKey, Authentication auth) {
        return flow.artifacts(requirementKey, actor(auth));
    }

    @GetMapping("/artifacts/{versionId}/content")
    public CollabProjectFlowService.ArtifactContentView content(@PathVariable String requirementKey,
            @PathVariable Long versionId, Authentication auth) {
        return flow.content(requirementKey, versionId, actor(auth));
    }

    @GetMapping("/events")
    public List<CollabProjectFlowService.EventView> events(@PathVariable String requirementKey, Authentication auth) {
        return flow.events(requirementKey, actor(auth));
    }

    @GetMapping("/task-prompt")
    public CollabProjectFlowService.TaskPromptView taskPrompt(@PathVariable String requirementKey,
            @RequestParam String stageKey, Authentication auth) {
        return flow.taskPrompt(requirementKey, stageKey, actor(auth));
    }

    private Long actor(Authentication auth) { return (Long) auth.getPrincipal(); }

    public record ActorRequest(@NotBlank String duty, @NotBlank String roleKey,
            @NotNull List<@NotNull Long> userIds, @NotNull @Positive Integer expectedStageVersion) {}
}
