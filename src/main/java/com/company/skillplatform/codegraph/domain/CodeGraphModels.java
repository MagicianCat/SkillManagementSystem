package com.company.skillplatform.codegraph.domain;

import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;

public final class CodeGraphModels {
    private CodeGraphModels() {}

    public record RepositoryInput(String repositoryKey, String logicalName, String commitSha, String treeSha,
                                  String sourceArtifactUri, String sourceSha256) {
        public RepositoryInput {
            repositoryKey = required(repositoryKey, "repositoryKey");
            logicalName = required(logicalName, "logicalName");
            commitSha = hex(commitSha, 40, "commitSha");
            treeSha = hex(treeSha, 40, "treeSha");
            sourceArtifactUri = safeSourceUri(sourceArtifactUri);
            sourceSha256 = hex(sourceSha256, 64, "sourceSha256");
        }
    }

    public record Artifact(String uri, String sha256, String engine, String engineVersion,
                           String adapterVersion, String contentRetention) {}

    public record GenerationCommand(long workflowRunId, String reason, String engineType, String engineVersion,
                                    String adapterVersion, String engineConfigHash, String groupConfigHash,
                                    List<RepositoryInput> repositories) {
        public GenerationCommand {
            if (workflowRunId < 1) throw new IllegalArgumentException("workflowRunId must be positive");
            reason = bounded(reason, "reason", 32);
            engineType = bounded(engineType, "engineType", 32);
            if (!"GITNEXUS".equals(engineType)) throw new IllegalArgumentException("Unsupported code graph engine");
            engineVersion = bounded(engineVersion, "engineVersion", 100);
            adapterVersion = bounded(adapterVersion, "adapterVersion", 100);
            engineConfigHash = bounded(engineConfigHash, "engineConfigHash", 64);
            groupConfigHash = bounded(groupConfigHash, "groupConfigHash", 64);
            repositories = List.copyOf(repositories);
            if (repositories.size() > 20) throw new IllegalArgumentException("At most 20 repositories are supported");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String hex(String value, int length, String field) {
        value = required(value, field).toLowerCase();
        if (value.length() != length || !value.matches("[0-9a-f]+"))
            throw new IllegalArgumentException(field + " must be " + length + " lowercase hexadecimal characters");
        return value;
    }

    private static String bounded(String value, String field, int maxLength) {
        value = required(value, field);
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return value;
    }

    private static String safeSourceUri(String value) {
        try {
            var uri = new URI(required(value, "sourceArtifactUri"));
            if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("sourceArtifactUri must be a query-free platform file URI");
            if (uri.getPath() == null || !uri.getPath().startsWith("/")) throw new IllegalArgumentException("sourceArtifactUri must be absolute");
            return uri.normalize().toString();
        } catch (URISyntaxException exception) { throw new IllegalArgumentException("sourceArtifactUri is invalid", exception); }
    }
}
