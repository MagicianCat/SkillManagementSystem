package com.company.skillplatform.codegraph.infrastructure;

import com.company.skillplatform.codegraph.domain.CodeGraphEnginePort;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.Artifact;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class CodeGraphWorkerClient implements CodeGraphEnginePort {
    private final RestClient client;
    private final CodeGraphWorkerProperties properties;

    public CodeGraphWorkerClient(RestClient.Builder builder, CodeGraphWorkerProperties properties) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        this.client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        this.properties = properties;
    }

    @Override
    public BuildHandle build(BuildRequest request) {
        ensureEnabled();
        var options = new LinkedHashMap<String, Object>();
        options.put("workers", properties.workers());
        options.put("analyzeTimeoutSeconds", properties.analyzeTimeoutSeconds());
        options.put("groupSyncTimeoutSeconds", properties.groupSyncTimeoutSeconds());
        options.putAll(request.options());
        var body = Map.of("schemaVersion", 1, "requestId", request.requestId(), "bundleKey", request.bundleKey(),
                "buildMode", "FULL", "repositories", request.repositories(), "options", options);
        try {
            var response = client.post().uri("/internal/code-graph/build")
                    .header("Authorization", "Bearer " + properties.token())
                    .header("Idempotency-Key", request.requestId())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(BuildAccepted.class);
            if (response == null || response.engineJobId() == null || response.engineJobId().isBlank())
                throw new CodeGraphWorkerException("CODE_GRAPH_WORKER_INVALID_RESPONSE", "Worker returned no engine job id", false);
            return new BuildHandle(response.engineJobId(), response.status());
        } catch (CodeGraphWorkerException exception) { throw exception; }
        catch (RestClientException exception) {
            throw new CodeGraphWorkerException("CODE_GRAPH_WORKER_UNAVAILABLE", "Code graph worker request failed", true, exception);
        }
    }

    @Override
    public BuildStatus status(String engineJobId) {
        ensureEnabled();
        if (engineJobId == null || !engineJobId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new CodeGraphWorkerException("CODE_GRAPH_ENGINE_JOB_ID_INVALID", "Engine job id is invalid", false);
        try {
            var response = client.get().uri("/internal/code-graph/jobs/{id}", engineJobId)
                    .header("Authorization", "Bearer " + properties.token()).retrieve().body(JobResponse.class);
            if (response == null) throw new CodeGraphWorkerException("CODE_GRAPH_WORKER_INVALID_RESPONSE", "Worker returned an empty job", false);
            var error = response.error();
            var artifact = response.artifact() == null ? null : response.artifact().toDomain();
            if (artifact != null) validateArtifact(artifact);
            return new BuildStatus(response.engineJobId(), State.valueOf(response.status()), response.progress(),
                    response.currentStep(), artifact,
                    error == null ? null : error.code(), error == null ? null : error.message());
        } catch (CodeGraphWorkerException exception) { throw exception; }
        catch (IllegalArgumentException | RestClientException exception) {
            throw new CodeGraphWorkerException("CODE_GRAPH_WORKER_INVALID_RESPONSE", "Code graph worker status is invalid", true, exception);
        }
    }

    private void ensureEnabled() {
        if (!properties.enabled()) throw new CodeGraphWorkerException("CODE_GRAPH_DISABLED", "Code graph worker is disabled", false);
        if (properties.token().isBlank()) throw new CodeGraphWorkerException("CODE_GRAPH_WORKER_TOKEN_MISSING", "Code graph worker token is not configured", false);
    }

    private void validateArtifact(Artifact artifact) {
        try {
            var uri = java.net.URI.create(artifact.uri());
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getQuery() != null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("unsafe artifact URI");
            if (artifact.sha256() == null || !artifact.sha256().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid artifact checksum");
        } catch (IllegalArgumentException exception) {
            throw new CodeGraphWorkerException("CODE_GRAPH_WORKER_INVALID_ARTIFACT", "Worker returned an invalid artifact descriptor", false, exception);
        }
    }

    private record BuildAccepted(int schemaVersion, String engineJobId, String status, String acceptedAt) {}
    private record JobResponse(int schemaVersion, String engineJobId, String status, int progress, String currentStep,
                               ArtifactResponse artifact, ErrorResponse error) {}
    private record ArtifactResponse(String artifactUri, String sha256, String engine, String engineVersion,
                                    String adapterVersion, String contentRetention) {
        Artifact toDomain() { return new Artifact(artifactUri, sha256, engine, engineVersion, adapterVersion, contentRetention); }
    }
    private record ErrorResponse(String code, String message, boolean retryable) {}

    public static class CodeGraphWorkerException extends RuntimeException {
        private final String code;
        private final boolean retryable;
        CodeGraphWorkerException(String code, String message, boolean retryable) { super(message); this.code = code; this.retryable = retryable; }
        CodeGraphWorkerException(String code, String message, boolean retryable, Throwable cause) { super(message, cause); this.code = code; this.retryable = retryable; }
        public String code() { return code; }
        public boolean retryable() { return retryable; }
    }
}
