package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

public interface CodeGraphMetadataStore {
    Map<String, CodeGraphReusePlanner.SnapshotMatch> findReadySnapshots(List<String> fingerprints);
    OptionalLong findReadyBundle(String bundleHash);
    long activateReused(GenerationCommand command, CodeGraphReusePlanner.Plan plan, String bundleHash, long bundleId);
    PendingJob createBuild(GenerationCommand command, CodeGraphReusePlanner.Plan plan, String bundleHash,
                           String requestId, String engineBundleKey);
    void attachEngineJob(long jobId, String engineJobId);
    PollableJob pollable(long jobId);
    void updateProgress(long jobId, int progress);
    boolean complete(long jobId, CodeGraphEnginePort.BuildStatus status);
    void fail(long jobId, String errorCode, String errorMessage);

    record PendingJob(long jobId, long bindingId, String requestId, String engineBundleKey) {}
    record PollableJob(long jobId, long workflowRunId, String engineJobId, String status) {}
}
