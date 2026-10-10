package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.codegraph.domain.CodeGraphModels.GenerationCommand;
import com.company.skillplatform.common.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class CodeGraphPreparationService {
    private final JdbcTemplate jdbc;
    private final CodeGraphGenerationCoordinator coordinator;

    public CodeGraphPreparationService(JdbcTemplate jdbc, CodeGraphGenerationCoordinator coordinator) {
        this.jdbc = jdbc; this.coordinator = coordinator;
    }

    public CodeGraphGenerationCoordinator.StartResult prepare(long actorId, GenerationCommand command) {
        return prepare(actorId, command, false);
    }

    /** Starts a build, optionally bypassing all snapshot/bundle reuse. */
    public CodeGraphGenerationCoordinator.StartResult prepare(long actorId, GenerationCommand command, boolean forceRebuild) {
        var access = access(command.workflowRunId(), actorId);
        if (!access.manager()) throw new BusinessException("PROJECT_MANAGE_FORBIDDEN", "Project manager role required", HttpStatus.FORBIDDEN);
        for (var repository : command.repositories()) {
            var count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run_git_repository WHERE workflow_run_id=? AND status='ACTIVE' AND logical_repository_key=? AND resolved_commit_sha=? AND resolved_tree_sha=?",
                    Integer.class, command.workflowRunId(), repository.repositoryKey(), repository.commitSha(), repository.treeSha());
            if (count == null || count == 0) throw new BusinessException("CODE_GRAPH_REPOSITORY_NOT_FROZEN",
                    "Code graph repositories must match the workflow frozen repository set", HttpStatus.CONFLICT);
        }
        return coordinator.start(command, forceRebuild);
    }

    public CodeGraphGenerationCoordinator.PollResult poll(long actorId, long workflowRunId, long jobId) {
        access(workflowRunId, actorId);
        var owned = jdbc.queryForObject("SELECT COUNT(*) FROM code_graph_generation_job WHERE id=? AND workflow_run_id=?", Integer.class, jobId, workflowRunId);
        if (owned == null || owned == 0) throw new BusinessException("CODE_GRAPH_JOB_NOT_FOUND", "Code graph job not found", HttpStatus.NOT_FOUND);
        return coordinator.poll(jobId);
    }

    private Access access(long workflowRunId, long actorId) {
        var rows = jdbc.query("SELECT p.created_by,m.role_key,m.status FROM workflow_run w JOIN virtual_project p ON p.id=w.project_id LEFT JOIN virtual_project_member m ON m.project_id=p.id AND m.user_id=? WHERE w.id=? AND p.status='ACTIVE'",
                (rs, row) -> new Access(rs.getLong(1) == actorId,
                        rs.getString(2), rs.getString(3)), actorId, workflowRunId);
        if (rows.isEmpty()) throw new BusinessException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND);
        var value = rows.get(0);
        var admin = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> "admin:identity".equals(a.getAuthority()));
        var activeMember = "ACTIVE".equals(value.memberStatus());
        if (!admin && !value.creator() && !activeMember) throw new BusinessException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND);
        return new Access(value.creator(), value.role(), value.memberStatus(),
                admin || value.creator() || activeMember && Set.of("OWNER", "MAINTAINER").contains(value.role()));
    }

    private record Access(boolean creator, String role, String memberStatus, boolean manager) {
        private Access(boolean creator, String role, String memberStatus) { this(creator, role, memberStatus, false); }
    }
}
