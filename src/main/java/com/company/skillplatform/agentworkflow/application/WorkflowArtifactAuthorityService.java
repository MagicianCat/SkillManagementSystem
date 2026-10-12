package com.company.skillplatform.agentworkflow.application;

import com.company.skillplatform.agent.domain.AgentRun;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.project.application.ProjectControlService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the deterministic side of workflow artifact creation.
 *
 * <p>The model supplies document prose only. Project, stage, artifact type, update target,
 * provenance and workflow bindings are derived from the authenticated Agent run.</p>
 */
@Service
public class WorkflowArtifactAuthorityService {
    private static final Pattern RUN_REF = Pattern.compile("^workflow-(\\d+)-agent-(\\d+)$");

    private final JdbcTemplate jdbc;
    private final ProjectControlService projects;

    public WorkflowArtifactAuthorityService(JdbcTemplate jdbc, ProjectControlService projects) {
        this.jdbc = jdbc;
        this.projects = projects;
    }

    public boolean supports(AgentRun agent) {
        return agent != null && agent.sessionKey() == null && agent.runRef() != null
                && RUN_REF.matcher(agent.runRef()).matches();
    }

    @Transactional
    public ProjectControlService.DocumentView save(AgentRun agent, String requestedType, String title,
                                                    String content, Long requestedArtifactId,
                                                    Object skillSnapshots, Object assumptions,
                                                    Object openQuestions) {
        Matcher matcher = RUN_REF.matcher(agent.runRef());
        if (!matcher.matches()) throw error("WORKFLOW_CONTEXT_REQUIRED", "Workflow context is required", HttpStatus.FORBIDDEN);
        long workflowRunId = Long.parseLong(matcher.group(1));
        long agentRunId = Long.parseLong(matcher.group(2));
        Map<String,Object> context = jdbc.queryForMap(
                "select w.id workflow_run_id,w.project_id,p.project_key,sr.id stage_run_id,sr.input_snapshot_json," +
                        "ar.id agent_run_id,ar.status,coalesce(nd.workflow_role,'' ) workflow_role," +
                        "coalesce(sd.artifact_type,'') artifact_type " +
                        "from agent_workflow_run ar join stage_run sr on sr.id=ar.stage_run_id " +
                        "join workflow_run w on w.id=sr.workflow_run_id join virtual_project p on p.id=w.project_id " +
                        "join workflow_template wt on wt.code=w.workflow_code " +
                        "join workflow_template_version wv on wv.workflow_template_id=wt.id and wv.version_no=w.workflow_version " +
                        "join workflow_stage_def sd on sd.workflow_version_id=wv.id and sd.stage_key=sr.stage_key " +
                        "join stage_agent_node_def nd on nd.stage_def_id=sd.id and nd.node_key=ar.node_key " +
                        "where w.id=? and ar.id=? for update", workflowRunId, agentRunId);
        String projectKey = String.valueOf(context.get("project_key"));
        if (!projectKey.equals(agent.projectKey())) throw error("WORKFLOW_PROJECT_MISMATCH", "Agent project does not match workflow project", HttpStatus.FORBIDDEN);
        if (!"AUTHOR".equalsIgnoreCase(String.valueOf(context.get("workflow_role"))))
            throw error("WORKFLOW_ARTIFACT_WRITE_FORBIDDEN", "Only the stage author can save its artifact", HttpStatus.FORBIDDEN);
        if (!List.of("STARTING", "RUNNING").contains(String.valueOf(context.get("status"))))
            throw error("WORKFLOW_AGENT_NOT_ACTIVE", "The workflow Agent is no longer active", HttpStatus.CONFLICT);
        String artifactType = String.valueOf(context.get("artifact_type")).toUpperCase(Locale.ROOT);
        if (artifactType.isBlank() || !artifactType.equalsIgnoreCase(requestedType))
            throw error("WORKFLOW_ARTIFACT_TYPE_MISMATCH", "Artifact type is fixed by the workflow stage", HttpStatus.BAD_REQUEST);

        long stageRunId = ((Number) context.get("stage_run_id")).longValue();
        List<Long> existing = jdbc.query(
                "select b.project_document_id from workflow_artifact_binding b " +
                        "join project_document_revision r on r.id=b.project_document_revision_id " +
                        "where b.workflow_run_id=? and b.stage_run_id=? and b.artifact_kind=? and b.relation_type='OUTPUT' " +
                        "order by r.revision_no desc,b.id desc limit 1",
                (rs, row) -> rs.getLong(1), workflowRunId, stageRunId, artifactType);
        Long targetDocumentId = existing.isEmpty() ? null : existing.get(0);
        if (requestedArtifactId != null && !requestedArtifactId.equals(targetDocumentId))
            throw error("WORKFLOW_ARTIFACT_TARGET_MISMATCH", "Artifact target is fixed by the workflow stage", HttpStatus.CONFLICT);

        List<Long> sourceDocumentIds = jdbc.query(
                "select distinct jt.document_id from stage_run sr join json_table(" +
                        "coalesce(sr.input_snapshot_json,json_array()), '$[*]' columns(document_id bigint path '$.documentId')) jt " +
                        "where sr.id=? and jt.document_id is not null",
                (rs, row) -> rs.getLong(1), stageRunId);
        ProjectControlService.DocumentView saved;
        if (targetDocumentId == null) {
            saved = projects.createAgentDocument(projectKey, new ProjectControlService.AgentDocument(
                    artifactType, title, content, agent.profileKey(), agent.runRef(), agent.runRef(),
                    skillSnapshots, assumptions, openQuestions, sourceDocumentIds), agent.userId(), "mcp:" + agent.runRef());
        } else {
            Integer versionNo = jdbc.queryForObject("select version_no from project_document where id=? and project_id=?",
                    Integer.class, targetDocumentId, context.get("project_id"));
            if (versionNo == null) throw error("WORKFLOW_ARTIFACT_TARGET_MISSING", "Workflow artifact target no longer exists", HttpStatus.CONFLICT);
            saved = projects.saveDraft(projectKey, targetDocumentId, new ProjectControlService.SaveDraft(
                    title, content, versionNo, "AGENT", agent.profileKey(), agent.runRef(), agent.runRef(),
                    skillSnapshots, assumptions, openQuestions, sourceDocumentIds), agent.userId(), "mcp:" + agent.runRef());
        }
        if (saved.draft() == null) throw error("WORKFLOW_ARTIFACT_REVISION_MISSING", "Saved artifact has no draft revision", HttpStatus.CONFLICT);
        jdbc.update("insert ignore into workflow_artifact_binding(time_created,workflow_run_id,stage_run_id,agent_run_id," +
                        "artifact_kind,project_document_id,project_document_revision_id,relation_type) " +
                        "values(now(3),?,?,?,?,?,?,'OUTPUT')",
                workflowRunId, stageRunId, agentRunId, artifactType, saved.id(), saved.draft().id());
        return saved;
    }

    private BusinessException error(String code, String message, HttpStatus status) {
        return new BusinessException(code, message, status);
    }
}
