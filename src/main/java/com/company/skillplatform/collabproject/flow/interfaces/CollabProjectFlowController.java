package com.company.skillplatform.collabproject.flow.interfaces;

import com.company.skillplatform.collabproject.flow.application.CollabProjectFlowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Stage, artifact, submission and context API for the independent collaboration project flow. */
@RestController
@RequestMapping("/api/v1/collab-projects")
public class CollabProjectFlowController {
    private final CollabProjectFlowService service;

    public CollabProjectFlowController(CollabProjectFlowService service) {
        this.service = service;
    }

    @GetMapping("/{projectKey}/workflow")
    public CollabProjectFlowService.WorkflowView workflow(@PathVariable String projectKey, Authentication auth) {
        return service.workflow(projectKey, actor(auth));
    }

    @PostMapping("/{projectKey}/stages/{stageKey}/artifacts")
    public CollabProjectFlowService.ArtifactView upload(@PathVariable String projectKey,
            @PathVariable String stageKey, @Valid @RequestBody ArtifactUpload request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey, Authentication auth) {
        return service.upload(projectKey, stageKey, request, idempotencyKey, actor(auth));
    }

    @PostMapping("/{projectKey}/stages/{stageKey}/submissions")
    public CollabProjectFlowService.SubmissionView submit(@PathVariable String projectKey,
            @PathVariable String stageKey, @Valid @RequestBody SubmissionRequest request,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey, Authentication auth) {
        return service.submit(projectKey, stageKey, request, idempotencyKey, actor(auth));
    }

    @PostMapping("/{projectKey}/submissions/{submissionId}/decision")
    public CollabProjectFlowService.SubmissionView decide(@PathVariable String projectKey,
            @PathVariable Long submissionId, @Valid @RequestBody DecisionRequest request, Authentication auth) {
        return service.decide(projectKey, submissionId, request, actor(auth));
    }

    @GetMapping("/{projectKey}/context")
    public CollabProjectFlowService.ContextView context(@PathVariable String projectKey,
            @RequestParam String stageKey, @RequestParam(defaultValue = "false") boolean includeHistory,
            Authentication auth) {
        return service.context(projectKey, stageKey, includeHistory, actor(auth));
    }

    @GetMapping("/{projectKey}/artifacts")
    public List<CollabProjectFlowService.ArtifactView> artifacts(@PathVariable String projectKey,
            Authentication auth) {
        return service.artifacts(projectKey, actor(auth));
    }

    @GetMapping("/{projectKey}/artifacts/{versionId}/content")
    public CollabProjectFlowService.ArtifactContentView content(@PathVariable String projectKey,
            @PathVariable Long versionId, Authentication auth) {
        return service.content(projectKey, versionId, actor(auth));
    }

    @GetMapping("/{projectKey}/events")
    public List<CollabProjectFlowService.EventView> events(@PathVariable String projectKey, Authentication auth) {
        return service.events(projectKey, actor(auth));
    }

    @GetMapping("/{projectKey}/task-prompt")
    public CollabProjectFlowService.TaskPromptView taskPrompt(@PathVariable String projectKey,
            @RequestParam String stageKey, Authentication auth) {
        return service.taskPrompt(projectKey, stageKey, actor(auth));
    }

    private Long actor(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }

    public record ArtifactUpload(@NotNull Long stageId, @Positive int attemptNo, @NotBlank String kind,
            @NotBlank String title, @NotBlank String sha256, @NotBlank String markdownContent,
            GitEvidence gitEvidence) {}

    public record GitEvidence(String repositoryUrl, String commitOrPullRequestUrl) {}

    public record SubmissionRequest(@NotNull Long stageId, @Positive int attemptNo,
            @NotNull Long artifactVersionId, @NotNull Long expectedStageVersion) {}

    public record DecisionRequest(@NotBlank String decision, String comment, @NotNull Long expectedVersion) {}
}
