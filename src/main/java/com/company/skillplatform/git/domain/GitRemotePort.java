package com.company.skillplatform.git.domain;

import java.time.Instant;
import java.util.List;

public interface GitRemotePort {
    RepositoryResolution resolve(String locator);
    List<RemoteBranch> branches(String locator);
    BranchHead head(String locator, String branch);
    List<CommitMetadata> commits(String locator, String branch, String oldSha, String newSha);
    /** Checks whether ancestorSha is reachable from targetSha and returns graph distance. */
    AncestorDistance ancestorDistance(String locator, String ancestorSha, String targetSha);
    /** Resolves a branch to immutable commit/tree IDs and creates a platform-owned tar source package. */
    FrozenRepository freeze(String locator, String branch);

    record RepositoryResolution(String repositoryPath, String normalizedUrl, String defaultBranch,
                                List<String> branches, String headCommit) {}
    record RemoteBranch(String name, String commitSha) {}
    record BranchHead(String branch, String commitSha) {}
    record FrozenRepository(String repositoryPath, String normalizedUrl, String branch, String commitSha,
                            String treeSha, String logicalRepositoryKey, String sourceArtifactUri,
                            String sourceSha256) {}
    record CommitMetadata(String commitSha, String parentSha, String authorName, String authorEmail,
                          Instant commitTime, String subject) {}
    record AncestorDistance(boolean ancestor, int distance) {}
}
