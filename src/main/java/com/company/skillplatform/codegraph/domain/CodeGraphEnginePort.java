package com.company.skillplatform.codegraph.domain;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.Artifact;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;

import java.util.List;
import java.util.Map;

public interface CodeGraphEnginePort {
    BuildHandle build(BuildRequest request);
    BuildStatus status(String engineJobId);

    record BuildRequest(String requestId, String bundleKey, List<RepositoryInput> repositories,
                        Map<String, Object> options) {
        public BuildRequest { repositories = List.copyOf(repositories); options = Map.copyOf(options); }
    }
    record BuildHandle(String engineJobId, String status) {}
    record BuildStatus(String engineJobId, State state, int progress, String currentStep,
                       Artifact artifact, String errorCode, String errorMessage) {}
    enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}
