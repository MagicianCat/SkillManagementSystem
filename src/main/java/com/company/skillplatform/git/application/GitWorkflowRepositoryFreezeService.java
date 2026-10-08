package com.company.skillplatform.git.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.RepositoryInput;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.git.domain.GitRemotePort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Freezes the active project repository set at workflow start for deterministic code-graph input. */
@Service
public class GitWorkflowRepositoryFreezeService {
    private final JdbcTemplate jdbc;
    private final GitRemotePort remote;

    public GitWorkflowRepositoryFreezeService(JdbcTemplate jdbc, GitRemotePort remote) {
        this.jdbc = jdbc;
        this.remote = remote;
    }

    @Transactional
    public List<FrozenRepository> freeze(long workflowRunId) {
        var rows = jdbc.queryForList("select id,normalized_url,tracked_branch from workflow_run_git_repository where workflow_run_id=? and status='ACTIVE' order by id", workflowRunId);
        if (rows.isEmpty()) throw new BusinessException("CODE_GRAPH_REPOSITORY_REQUIRED", "At least one active Git repository is required", HttpStatus.CONFLICT);
        var result = new java.util.ArrayList<FrozenRepository>(rows.size());
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String url = String.valueOf(row.get("normalized_url"));
            String branch = String.valueOf(row.get("tracked_branch"));
            GitRemotePort.FrozenRepository frozen = remote.freeze(url, branch);
            jdbc.update("update workflow_run_git_repository set resolved_commit_sha=?,resolved_tree_sha=?,resolved_at=now(3),logical_repository_key=?,source_artifact_uri=?,source_sha256=? where id=? and workflow_run_id=? and status='ACTIVE'",
                    frozen.commitSha(), frozen.treeSha(), frozen.logicalRepositoryKey(), frozen.sourceArtifactUri(), frozen.sourceSha256(), id, workflowRunId);
            result.add(new FrozenRepository(id, frozen));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public boolean isFresh(long workflowRunId) {
        var rows = jdbc.queryForList("select normalized_url,tracked_branch,resolved_commit_sha from workflow_run_git_repository where workflow_run_id=? and status='ACTIVE' order by id", workflowRunId);
        if (rows.isEmpty()) return false;
        return rows.stream().allMatch(row -> String.valueOf(row.get("resolved_commit_sha")).equals(
                remote.head(String.valueOf(row.get("normalized_url")), String.valueOf(row.get("tracked_branch"))).commitSha()));
    }

    @Transactional(readOnly = true)
    public List<RepositoryInput> inputs(long workflowRunId) {
        return jdbc.query("select project_git_repository_id,logical_repository_key,resolved_commit_sha,resolved_tree_sha,source_artifact_uri,source_sha256 from workflow_run_git_repository where workflow_run_id=? and status='ACTIVE' and resolved_commit_sha is not null and resolved_tree_sha is not null and source_artifact_uri is not null and source_sha256 is not null order by project_git_repository_id",
                (rs, row) -> new RepositoryInput(rs.getString(2), "repo-" + rs.getLong(1), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), workflowRunId);
    }

    public record FrozenRepository(long workflowRepositoryId, GitRemotePort.FrozenRepository source) {}
}
