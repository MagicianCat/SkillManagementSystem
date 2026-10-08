package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphFingerprint;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeGraphGenerationCoordinator {
    private final CodeGraphEnginePort engine;
    private final CodeGraphMetadataStore store;
    private final CodeGraphLifecyclePublisher lifecycle;
    private final CodeGraphAncestorResolver ancestors;
    private final CodeGraphSemanticIndexTrigger semanticIndex;

    public CodeGraphGenerationCoordinator(CodeGraphEnginePort engine, CodeGraphMetadataStore store,
                                          CodeGraphLifecyclePublisher lifecycle) {
        this(engine, store, lifecycle, null, jobId -> {});
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CodeGraphGenerationCoordinator(CodeGraphEnginePort engine, CodeGraphMetadataStore store,
                                          CodeGraphLifecyclePublisher lifecycle,
                                          CodeGraphAncestorResolver ancestors,
                                          CodeGraphSemanticIndexTrigger semanticIndex) {
        this.engine = engine; this.store = store; this.lifecycle = lifecycle; this.ancestors = ancestors;
        this.semanticIndex = semanticIndex;
    }

    public StartResult start(GenerationCommand command) {
        validate(command);
        var planner = new CodeGraphReusePlanner(store::findReadySnapshots,
                repository -> store.findCompatibleSnapshots(repository, command.engineType(), command.engineVersion(),
                        command.adapterVersion(), command.engineConfigHash()),
                (repository, candidate) -> ancestors == null ? java.util.OptionalInt.empty() : ancestors.distance(command.workflowRunId(), repository, candidate));
        var plan = planner.plan(command.repositories(), command.engineType(), command.engineVersion(),
                command.adapterVersion(), command.engineConfigHash());
        var bundleHash = CodeGraphFingerprint.bundle(plan.repositories().stream().map(item ->
                new CodeGraphFingerprint.BundleEntry(item.repository().logicalName(), item.fingerprint())).toList(),
                command.groupConfigHash());
        if (plan.fullyReusable()) {
            var ready = store.findReadyBundle(bundleHash);
            if (ready.isPresent()) {
                var jobId = store.activateReused(command, plan, bundleHash, ready.getAsLong());
                lifecycle.event(command.workflowRunId(), "code_graph.bundle.ready", Map.of("jobId", jobId, "reused", true));
                lifecycle.terminal(command.workflowRunId(), jobId, true, null);
                requestSemanticIndex(jobId);
                return new StartResult(jobId, null, "READY", true, bundleHash);
            }
        }
        var requestId = UUID.randomUUID().toString();
        var bundleKey = "cg-" + bundleHash.substring(0, 24);
        var pending = store.createBuild(command, plan, bundleHash, requestId, bundleKey);
        lifecycle.event(command.workflowRunId(), "code_graph.preparation.started", Map.of("jobId", pending.jobId(), "repositoryCount", command.repositories().size()));
        try {
            var repositories = new java.util.LinkedHashMap<String, Object>();
            plan.repositories().forEach(item -> {
                var reuse = new java.util.LinkedHashMap<String, Object>();
                reuse.put("mode", switch (item.decision()) {
                    case REUSE_EXACT -> "EXACT";
                    case INCREMENTAL -> "INCREMENTAL";
                    case FULL_REQUIRED -> "FULL";
                });
                var base = item.baseSnapshot();
                if (base != null) {
                    reuse.put("baseCommitSha", base.commitSha());
                    reuse.put("baseTreeSha", base.treeSha());
                    reuse.put("baseArtifactUri", base.artifactUri());
                    reuse.put("baseArtifactSha256", base.artifactSha256());
                    reuse.put("baseArtifactRepositoryAlias", base.artifactRepositoryAlias() == null ? item.repository().logicalName() : base.artifactRepositoryAlias());
                }
                repositories.put(item.repository().logicalName(), reuse);
            });
            var buildMode = buildMode(plan);
            var reusePlan = Map.of("repositories", repositories);
            var handle = engine.build(new CodeGraphEnginePort.BuildRequest(requestId, bundleKey,
                    command.repositories(), Map.of("reusePlan", reusePlan,
                            "buildMode", buildMode)));
            store.attachEngineJob(pending.jobId(), handle.engineJobId());
            return new StartResult(pending.jobId(), handle.engineJobId(), "BUILDING", false, bundleHash);
        } catch (RuntimeException exception) {
            store.fail(pending.jobId(), "CODE_GRAPH_BUILD_SUBMIT_FAILED", safeMessage(exception));
            lifecycle.event(command.workflowRunId(), "code_graph.preparation.failed", Map.of("jobId", pending.jobId(), "errorCode", "CODE_GRAPH_BUILD_SUBMIT_FAILED"));
            lifecycle.terminal(command.workflowRunId(), pending.jobId(), false, safeMessage(exception));
            throw exception;
        }
    }

    public PollResult poll(long jobId) {
        var job = store.pollable(jobId);
        if ("READY".equals(job.status()) || "FAILED".equals(job.status()))
            return new PollResult(jobId, job.status(), 100);
        var status = engine.status(job.engineJobId());
        switch (status.state()) {
            case QUEUED, RUNNING -> store.updateProgress(jobId, status.progress());
            case SUCCEEDED -> {
                if (status.artifact() == null) {
                    store.fail(jobId, "CODE_GRAPH_ARTIFACT_MISSING", "Worker succeeded without an artifact");
                    return new PollResult(jobId, "FAILED", status.progress());
                }
                if (!store.complete(jobId, status)) {
                    lifecycle.event(job.workflowRunId(), "code_graph.preparation.failed", Map.of("jobId", jobId, "errorCode", "CODE_GRAPH_ARTIFACT_INCOMPATIBLE"));
                    lifecycle.terminal(job.workflowRunId(), jobId, false, "Worker artifact is incompatible");
                    return new PollResult(jobId, "FAILED", status.progress());
                }
                lifecycle.event(job.workflowRunId(), "code_graph.bundle.ready", Map.of("jobId", jobId, "reused", false));
                lifecycle.terminal(job.workflowRunId(), jobId, true, null);
                requestSemanticIndex(jobId);
            }
            case FAILED, CANCELLED -> {
                var errorCode = status.errorCode() == null ? "CODE_GRAPH_BUILD_FAILED" : status.errorCode();
                var errorMessage = status.errorMessage() == null ? "Code graph build did not complete" : status.errorMessage();
                store.fail(jobId, errorCode, errorMessage);
                lifecycle.event(job.workflowRunId(), "code_graph.preparation.failed", Map.of("jobId", jobId, "errorCode", errorCode));
                lifecycle.terminal(job.workflowRunId(), jobId, false, errorMessage);
            }
        }
        var mapped = switch (status.state()) { case SUCCEEDED -> "READY"; case FAILED, CANCELLED -> "FAILED"; default -> "BUILDING"; };
        return new PollResult(jobId, mapped, status.progress());
    }

    private void validate(GenerationCommand command) {
        if (command.repositories().isEmpty()) throw new IllegalArgumentException("At least one repository is required");
        var aliases = new HashSet<String>();
        command.repositories().forEach(repository -> {
            if (!aliases.add(repository.logicalName())) throw new IllegalArgumentException("Repository aliases must be unique");
        });
    }

    static String buildMode(CodeGraphReusePlanner.Plan plan) {
        var hasFull = plan.repositories().stream().anyMatch(item -> item.decision() == CodeGraphReusePlanner.Decision.FULL_REQUIRED);
        var hasIncremental = plan.repositories().stream().anyMatch(item -> item.decision() == CodeGraphReusePlanner.Decision.INCREMENTAL);
        var hasReuse = plan.repositories().stream().anyMatch(item -> item.decision() != CodeGraphReusePlanner.Decision.FULL_REQUIRED);
        return !hasReuse ? "FULL" : (!hasFull && hasIncremental ? "INCREMENTAL" : "MIXED");
    }

    private String safeMessage(RuntimeException exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length()));
    }

    private void requestSemanticIndex(long jobId) {
        try { semanticIndex.requestForJob(jobId); }
        catch (RuntimeException ignored) { /* Optional sidecar must never reverse structural readiness. */ }
    }

    public record StartResult(long jobId, String engineJobId, String status, boolean reused, String bundleHash) {}
    public record PollResult(long jobId, String status, int progress) {}
}
