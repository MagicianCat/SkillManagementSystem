package com.company.skillplatform.collabproject.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persists Feishu work in the caller's database transaction. */
@Service
public class CollabFeishuOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CollabFeishuOutbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void enqueueCreateChat(Long projectId, String projectKey) {
        jdbc.update("INSERT IGNORE INTO collab_feishu_binding(project_id,status,time_created,time_updated) VALUES(?,'PENDING',NOW(3),NOW(3))", projectId);
        enqueue(projectId, "CREATE_CHAT", "PROJECT", projectId, "chat:" + projectKey,
                Map.of("projectKey", projectKey));
    }

    public void enqueueMemberSync(Long projectId, Long userId, String action) {
        if (!"ADD_MEMBER".equals(action) && !"REMOVE_MEMBER".equals(action))
            throw new IllegalArgumentException("Unsupported member sync action");
        enqueue(projectId, action, "MEMBER", userId,
                "member:" + projectId + ":" + userId + ":" + UUID.randomUUID(),
                Map.of("userId", userId));
    }

    public void enqueuePublish(Long projectId, Long artifactVersionId) {
        jdbc.update("INSERT IGNORE INTO collab_feishu_publication(artifact_version_id,publish_status,time_created,time_updated) VALUES(?,'PENDING',NOW(3),NOW(3))", artifactVersionId);
        enqueue(projectId, "PUBLISH_ARTIFACT", "ARTIFACT_VERSION", artifactVersionId,
                "publish:" + artifactVersionId, Map.of("artifactVersionId", artifactVersionId));
    }

    public void enqueueApprovalReminder(Long projectId, Long approvalId) {
        enqueue(projectId, "APPROVAL_REMINDER", "APPROVAL", approvalId,
                "approval-reviewer:" + approvalId, Map.of("approvalId", approvalId));
    }

    public void enqueueWikiFolder(Long projectId) {
        enqueue(projectId, "CREATE_WIKI_FOLDER", "PROJECT", projectId,
                "wiki-folder:" + projectId, Map.of("projectId", projectId));
    }

    public void enqueueInitialStageHandoff(Long projectId, Long stageId, String projectKey) {
        enqueue(projectId, "STAGE_HANDOFF", "STAGE", stageId,
                "stage-handoff:first:" + projectKey + ":" + stageId, Map.of("stageId", stageId, "initial", true));
    }

    public void enqueueStageHandoff(Long projectId, Long nextStageId, Long submissionId) {
        enqueue(projectId, "STAGE_HANDOFF", "STAGE", nextStageId,
                "stage-handoff:submission:" + submissionId + ":" + nextStageId,
                Map.of("stageId", nextStageId, "submissionId", submissionId, "initial", false));
    }

    public void enqueueStageActivation(Long projectId, Long stageId, int stageVersion) {
        enqueue(projectId, "STAGE_HANDOFF", "STAGE", stageId,
                "stage-handoff:activation:" + stageId + ":" + stageVersion,
                Map.of("stageId", stageId, "initial", false));
    }

    public void enqueueStageReworkReminder(Long projectId, Long stageId, Long submissionId) {
        enqueue(projectId, "STAGE_REWORK", "STAGE", stageId,
                "stage-rework:" + submissionId,
                Map.of("stageId", stageId, "submissionId", submissionId));
    }

    public void enqueueProjectCompleted(Long projectId) {
        enqueue(projectId, "PROJECT_COMPLETED", "PROJECT", projectId,
                "project-completed:" + projectId, Map.of("projectId", projectId));
    }

    private void enqueue(Long projectId, String type, String aggregateType, Long aggregateId,
                         String key, Map<String, ?> payload) {
        try {
            jdbc.update("INSERT IGNORE INTO collab_outbox(project_id,event_type,aggregate_type,aggregate_id,unique_dedupe_key,payload_json,status,attempts,next_attempt_at,time_created,time_updated) "
                    + "VALUES(?,?,?,?,?,?,'PENDING',0,NOW(3),NOW(3),NOW(3))",
                    projectId, type, aggregateType, aggregateId, key, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Feishu event payload", e);
        }
    }
}
