package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphFingerprint;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeGraphGenerationCoordinator {
    private final CodeGraphEnginePort engine;
    private final CodeGraphMetadataStore store;
    private final CodeGraphLifecyclePublisher lifecycle;

    public CodeGraphGenerationCoordinator(CodeGraphEnginePort engine, CodeGraphMetadataStore store,
                                          CodeGraphLifecyclePublisher lifecycle) {
        this.engine = engine; this.store = store; this.lifecycle = lifecycle;
    }

    public StartResult start(GenerationCommand command) {
        validate(command);
        var planner = new CodeGraphReusePlanner(store::findReadySnapshots);
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
                return new StartResult(jobId, null, "READY", true, bundleHash);
            }
        }
        var requestId = UUID.randomUUID().toString();
        var bundleKey = "cg-" + bundleHash.substring(0, 24);
        var pending = store.createBuild(command, plan, bundleHash, requestId, bundleKey);
        lifecycle.event(command.workflowRunId(), "code_graph.preparation.started", Map.of("jobId", pending.jobId(), "repositoryCount", command.repositories().size()));
        try {
            var handle = engine.build(new CodeGraphEnginePort.BuildRequest(requestId, bundleKey,
                    command.repositories(), Map.of()));
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

    private String safeMessage(RuntimeException exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length()));
    }

    public record StartResult(long jobId, String engineJobId, String status, boolean reused, String bundleHash) {}
    public record PollResult(long jobId, String status, int progress) {}
}
