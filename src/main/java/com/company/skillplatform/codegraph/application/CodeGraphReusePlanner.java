package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphFingerprint;
import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.OptionalInt;

public final class CodeGraphReusePlanner {
    private final Function<List<String>, Map<String, SnapshotMatch>> readyLookup;
    private final Function<RepositoryInput, List<SnapshotCandidate>> candidateLookup;
    private final BiFunction<RepositoryInput, SnapshotCandidate, OptionalInt> ancestorDistance;

    public CodeGraphReusePlanner(Function<List<String>, Map<String, SnapshotMatch>> readyLookup) {
        this(readyLookup, ignored -> List.of(), (repository, candidate) -> OptionalInt.empty());
    }

    public CodeGraphReusePlanner(Function<List<String>, Map<String, SnapshotMatch>> readyLookup,
                                 Function<RepositoryInput, List<SnapshotCandidate>> candidateLookup,
                                 BiFunction<RepositoryInput, SnapshotCandidate, OptionalInt> ancestorDistance) {
        this.readyLookup = readyLookup;
        this.candidateLookup = candidateLookup;
        this.ancestorDistance = ancestorDistance;
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
            if (reusable) return new RepositoryPlan(repositories.get(index), fingerprint, Decision.REUSE_EXACT,
                    match.snapshotId(), null, 0, match.asCandidate());
            var repository = repositories.get(index);
            var ancestor = candidateLookup.apply(repository).stream()
                    .filter(candidate -> "READY".equals(candidate.status()))
                    .map(candidate -> new AncestorCandidate(candidate, ancestorDistance.apply(repository, candidate)))
                    .filter(value -> value.distance().isPresent())
                    .min(java.util.Comparator.comparingInt(value -> value.distance().getAsInt()));
            if (ancestor.isPresent()) {
                var value = ancestor.get();
                return new RepositoryPlan(repository, fingerprint, Decision.INCREMENTAL,
                        null, value.candidate().snapshotId(), value.distance().getAsInt(), value.candidate());
            }
            return new RepositoryPlan(repository, fingerprint, Decision.FULL_REQUIRED, null, null, null, null);
        }).toList();
        return new Plan(results, results.stream().allMatch(item -> item.decision() == Decision.REUSE_EXACT));
    }

    public enum Decision { REUSE_EXACT, INCREMENTAL, FULL_REQUIRED }
    public record SnapshotMatch(long snapshotId, String fingerprint, String status, String commitSha, String treeSha,
                                String artifactUri, String artifactSha256, String artifactRepositoryAlias) {
        public SnapshotMatch(long snapshotId, String fingerprint, String status) {
            this(snapshotId, fingerprint, status, null, null, null, null, null);
        }
        SnapshotCandidate asCandidate() {
            return new SnapshotCandidate(snapshotId, fingerprint, status, commitSha, treeSha,
                    artifactUri, artifactSha256, artifactRepositoryAlias);
        }
    }
    public record SnapshotCandidate(long snapshotId, String fingerprint, String status, String commitSha, String treeSha,
                                    String artifactUri, String artifactSha256, String artifactRepositoryAlias) {
        public SnapshotCandidate(long snapshotId, String fingerprint, String status, String commitSha, String treeSha) {
            this(snapshotId, fingerprint, status, commitSha, treeSha, null, null, null);
        }
    }
    public record RepositoryPlan(RepositoryInput repository, String fingerprint, Decision decision, Long snapshotId,
                                 Long baseSnapshotId, Integer commitDistance, SnapshotCandidate baseSnapshot) {
        public RepositoryPlan(RepositoryInput repository, String fingerprint, Decision decision, Long snapshotId) {
            this(repository, fingerprint, decision, snapshotId, null, null, null);
        }
    }
    public record Plan(List<RepositoryPlan> repositories, boolean fullyReusable) {}
    private record AncestorCandidate(SnapshotCandidate candidate, OptionalInt distance) {}
}
