package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphFingerprint;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class CodeGraphReusePlanner {
    private final Function<List<String>, Map<String, SnapshotMatch>> readyLookup;

    public CodeGraphReusePlanner(Function<List<String>, Map<String, SnapshotMatch>> readyLookup) {
        this.readyLookup = readyLookup;
    }

    public Plan plan(List<RepositoryInput> repositories, String engineType, String engineVersion,
                     String adapterVersion, String engineConfigHash) {
        var fingerprints = repositories.stream().map(repository -> CodeGraphFingerprint.repository(
                new CodeGraphFingerprint.RepositoryIdentity(repository.repositoryKey(), repository.treeSha(),
                        engineType, engineVersion, adapterVersion, engineConfigHash))).toList();
        var matches = readyLookup.apply(fingerprints);
        var results = java.util.stream.IntStream.range(0, repositories.size()).mapToObj(index -> {
            var fingerprint = fingerprints.get(index);
            var match = matches.get(fingerprint);
            var reusable = match != null && "READY".equals(match.status());
            return new RepositoryPlan(repositories.get(index), fingerprint,
                    reusable ? Decision.REUSE_EXACT : Decision.FULL_REQUIRED, reusable ? match.snapshotId() : null);
        }).toList();
        return new Plan(results, results.stream().allMatch(item -> item.decision() == Decision.REUSE_EXACT));
    }

    public enum Decision { REUSE_EXACT, FULL_REQUIRED }
    public record SnapshotMatch(long snapshotId, String fingerprint, String status) {}
    public record RepositoryPlan(RepositoryInput repository, String fingerprint, Decision decision, Long snapshotId) {}
    public record Plan(List<RepositoryPlan> repositories, boolean fullyReusable) {}
}
