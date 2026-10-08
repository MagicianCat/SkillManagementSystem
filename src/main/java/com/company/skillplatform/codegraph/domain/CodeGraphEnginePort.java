package com.company.skillplatform.codegraph.domain;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.Artifact;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;

import java.util.List;
import java.util.Map;

public interface CodeGraphEnginePort {
    BuildHandle build(BuildRequest request);
    BuildStatus status(String engineJobId);
    default QueryResult query(QueryRequest request) {
        throw new UnsupportedOperationException("Code graph query is not supported by this engine");
    }

    record QueryRequest(String operation, GraphRef graph, String repository, Map<String, Object> parameters) {
        public QueryRequest {
            operation = operation == null ? "" : operation.trim().toLowerCase();
            operation = switch (operation) { case "search" -> "query"; case "node" -> "context"; default -> operation; };
            if (!operation.matches("overview|query|context|impact|trace|route-map")) throw new IllegalArgumentException("Unsupported code graph query");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }
    record GraphRef(String artifactUri, String artifactSha256, List<GraphRepository> repositories) {
        public GraphRef { repositories = List.copyOf(repositories == null ? List.of() : repositories); }
    }
    record GraphRepository(String logicalName, String alias) {}
    record QueryResult(String operation, Map<String, Object> data) {
        public QueryResult { data = data == null ? Map.of() : Map.copyOf(data); }
    }

    record BuildRequest(String requestId, String bundleKey, List<RepositoryInput> repositories,
                        Map<String, Object> options) {
        public BuildRequest { repositories = List.copyOf(repositories); options = Map.copyOf(options); }
    }
    record BuildHandle(String engineJobId, String status) {}
    record BuildStatus(String engineJobId, State state, int progress, String currentStep,
                       Artifact artifact, String errorCode, String errorMessage) {}
    enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
}
