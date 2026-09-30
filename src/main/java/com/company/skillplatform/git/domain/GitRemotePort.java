package com.company.skillplatform.git.domain;

import java.time.Instant;
import java.util.List;

public interface GitRemotePort {
    RepositoryResolution resolve(String locator);
    List<RemoteBranch> branches(String locator);
    BranchHead head(String locator, String branch);
    List<CommitMetadata> commits(String locator, String branch, String oldSha, String newSha);

    record RepositoryResolution(String repositoryPath, String normalizedUrl, String defaultBranch,
                                List<String> branches, String headCommit) {}
    record RemoteBranch(String name, String commitSha) {}
    record BranchHead(String branch, String commitSha) {}
    record CommitMetadata(String commitSha, String parentSha, String authorName, String authorEmail,
                          Instant commitTime, String subject) {}
}
