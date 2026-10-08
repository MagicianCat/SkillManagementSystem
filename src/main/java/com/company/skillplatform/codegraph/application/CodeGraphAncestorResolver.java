package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.git.domain.GitRemotePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;

/** Resolves ancestry against the frozen repository remote; the planner never trusts commit timestamps. */
@Component
public class CodeGraphAncestorResolver {
    private final JdbcTemplate jdbc;
    private final GitRemotePort remote;

    public CodeGraphAncestorResolver(JdbcTemplate jdbc, GitRemotePort remote) {
        this.jdbc = jdbc;
        this.remote = remote;
    }

    public OptionalInt distance(long workflowRunId, RepositoryInput target,
                               CodeGraphReusePlanner.SnapshotCandidate candidate) {
        var urls = jdbc.query("SELECT normalized_url FROM workflow_run_git_repository WHERE workflow_run_id=? AND logical_repository_key=? AND status='ACTIVE' LIMIT 1",
                (rs, row) -> rs.getString(1), workflowRunId, target.repositoryKey());
        if (urls.isEmpty()) return OptionalInt.empty();
        try {
            var result = remote.ancestorDistance(urls.get(0), candidate.commitSha(), target.commitSha());
            return result.ancestor() ? OptionalInt.of(result.distance()) : OptionalInt.empty();
        } catch (RuntimeException ignored) {
            return OptionalInt.empty();
        }
    }
}
