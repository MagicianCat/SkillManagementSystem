package com.company.skillplatform.project.application;

import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.project.infrastructure.entity.*;
import com.company.skillplatform.project.infrastructure.repository.*;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** SMS-owned document-agent sessions. DSH sessions never enter this service. */
@Service
public class DocumentAgentSessionService {
    private static final Set<String> PROFILES = Set.of("requirement-analysis/v1", "prd-authoring/v1", "architecture-design/v1", "ui-design/v1");
    private static final Map<String, String> DOCUMENT_TYPES = Map.of(
            "requirement-analysis/v1", "REQUIREMENT", "prd-authoring/v1", "PRD",
            "architecture-design/v1", "ARCHITECTURE", "ui-design/v1", "UI_DESIGN");
    private final ProjectControlService projects;
    private final ProjectDocumentRepository documents;
    private final VirtualProjectRepository virtualProjects;
    private final IamUserRepository users;
    private final DocumentAgentSessionRepository sessions;
    private final DocumentAgentJobRepository jobs;
    private final DocumentAgentJobContextRepository contexts;
    private final DocumentAgentJobEventRepository events;
    private final AgentRunService runs;
    private final FeishuDocumentMcpProxy feishu;
    private final ObjectMapper mapper;
    private final RestClient gateway;
    private final String serviceToken;

    public DocumentAgentSessionService(ProjectControlService projects, ProjectDocumentRepository documents,
                                       VirtualProjectRepository virtualProjects, IamUserRepository users,
                                       DocumentAgentSessionRepository sessions, DocumentAgentJobRepository jobs,
                                       DocumentAgentJobContextRepository contexts, DocumentAgentJobEventRepository events,
                                       AgentRunService runs, FeishuDocumentMcpProxy feishu, ObjectMapper mapper,
                                       RestClient.Builder builder,
                                       @Value("${skill-platform.document-agent.gateway-url:http://127.0.0.1:8090}") String gatewayUrl,
                                       @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String serviceToken) {
        this.projects = projects; this.documents = documents; this.virtualProjects = virtualProjects; this.users = users; this.sessions = sessions; this.jobs = jobs;
        this.contexts = contexts; this.events = events; this.runs = runs; this.feishu = feishu; this.mapper = mapper;
        this.gateway = builder.baseUrl(gatewayUrl).build(); this.serviceToken = serviceToken;
    }

    @Transactional(readOnly = true)
    public List<SessionView> list(String projectKey, Long actorId, int limit) {
        List<DocumentAgentSessionEntity> values = projectKey == null || projectKey.isBlank()
                ? sessions.findByOwnerIdOrderByLastActivityAtDesc(actorId, org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                : sessions.findByProjectProjectKeyAndOwnerIdOrderByLastActivityAtDesc(projectKey, actorId, org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
        return values.stream().map(this::sessionView).toList();
    }

    public SessionView create(String projectKey, Long actorId, String profileKey, String mode, Long documentId, String title, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required", HttpStatus.BAD_REQUEST);
        DocumentAgentSessionEntity existing = sessions.findByOwnerIdAndProjectProjectKeyAndIdempotencyKey(actorId, projectKey, idempotencyKey).orElse(null);
        if (existing != null) return sessionView(existing);
        requireProfile(profileKey);
        projects.assertAgentAccess(projectKey, actorId);
        ProjectDocumentEntity document = documentId == null ? null : document(projectKey, documentId, actorId);
        if ("EXISTING".equalsIgnoreCase(mode) && document == null) throw error("DOCUMENT_AGENT_TARGET_REQUIRED", "An existing target document is required", HttpStatus.BAD_REQUEST);
        if (document != null && !DOCUMENT_TYPES.get(profileKey).equals(document.getDocumentType())) throw error("DOCUMENT_AGENT_PROFILE_TARGET_MISMATCH", "Profile does not match target document", HttpStatus.BAD_REQUEST);
        if (document == null && (title == null || title.isBlank())) throw error("DOCUMENT_AGENT_TITLE_REQUIRED", "A title is required for a new document", HttpStatus.BAD_REQUEST);
        Instant now = Instant.now();
        DocumentAgentSessionEntity session = new DocumentAgentSessionEntity(projectEntity(projectKey, actorId), user(actorId), document,
                profileKey, title, idempotencyKey, "", now.plusSeconds(30L * 24 * 60 * 60), now);
        sessions.saveAndFlush(session);
        AgentRunService.IssuedRun issued = runs.issueDocumentSessionRun(actorId, projectKey, documentId, profileKey, session.getSessionKey());
        session.setMcpTokenHash(sha256(issued.token()));
        sessions.saveAndFlush(session);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("session_id", session.getSessionKey()); body.put("actor_id", String.valueOf(actorId)); body.put("project_id", projectKey);
            body.put("profile_key", profileKey); body.put("document_id", documentId == null ? null : String.valueOf(documentId));
            body.put("mcp_token", issued.token()); body.put("idempotency_key", idempotencyKey);
            Map<?, ?> result = gateway.post().uri("/internal/v1/document-sessions").header("X-SMS-Service-Token", serviceToken).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
            Map<?, ?> runtime = result == null ? Map.of() : result;
            session.runtimeBound(text(runtime, "session_id"), text(runtime, "conversation_id"), text(runtime, "workspace_id"));
            sessions.save(session);
            return sessionView(session);
        } catch (RuntimeException failure) {
            session.fail(); sessions.save(session);
            throw gatewayError(failure, "DOCUMENT_AGENT_GATEWAY_UNAVAILABLE");
        }
    }

    @Transactional
    public SessionView get(String sessionKey, Long actorId) { return sessionView(access(sessionKey, actorId)); }

    public void close(String sessionKey, Long actorId) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        if ("CLOSED".equals(session.getStatus()) || "EXPIRED".equals(session.getStatus())) return;
        DocumentAgentJobEntity active = jobs.findFirstBySessionAndStatusInOrderBySequenceNoDesc(session, List.of("QUEUED", "DISPATCHING", "RUNNING")).orElse(null);
        if (active != null && active.getRuntimeJobId() != null) {
            try { gateway.post().uri("/internal/v1/document-jobs/{id}/cancel", active.getRuntimeJobId()).header("X-SMS-Service-Token", serviceToken).retrieve().toBodilessEntity(); } catch (RuntimeException ignored) { active.fail("GATEWAY_UNAVAILABLE", "Gateway could not be cancelled", true); jobs.save(active); }
        }
        try { gateway.delete().uri("/internal/v1/document-sessions/{id}", session.getSessionKey()).header("X-SMS-Service-Token", serviceToken).retrieve().toBodilessEntity(); } catch (RuntimeException ignored) { /* local close remains authoritative */ }
        session.close(Instant.now()); sessions.save(session);
    }

    @Transactional
    public JobView turn(String sessionKey, Long actorId, String instruction, String idempotencyKey,
                        List<Long> sourceArtifactIds, List<FeishuContext> feishuDocuments) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        if (!"ACTIVE".equals(session.getStatus())) throw error("DOCUMENT_AGENT_SESSION_CLOSED", "Document agent session is not active", HttpStatus.CONFLICT);
        DocumentAgentJobEntity existing = jobs.findBySessionAndIdempotencyKey(session, idempotencyKey).orElse(null);
        if (existing != null) return jobView(existing);
        jobs.findFirstBySessionAndStatusInOrderBySequenceNoDesc(session, List.of("QUEUED", "DISPATCHING", "RUNNING")).ifPresent(value -> { throw error("DOCUMENT_AGENT_JOB_ACTIVE", "A document job is already active", HttpStatus.CONFLICT); });
        List<FeishuContext> selectedFeishu = feishuDocuments == null ? List.of() : feishuDocuments.stream().filter(Objects::nonNull).distinct().toList();
        if (selectedFeishu.size() > 10) throw error("DOCUMENT_AGENT_CONTEXT_LIMIT", "At most 10 Feishu documents may be selected", HttpStatus.BAD_REQUEST);
        int next = jobs.findTopBySessionOrderBySequenceNoDesc(session).map(value -> value.getSequenceNo() + 1).orElse(1);
        DocumentAgentJobEntity job = jobs.saveAndFlush(new DocumentAgentJobEntity(session, next, idempotencyKey, instruction));
        int ordinal = 0;
        if (sourceArtifactIds != null) for (Long id : new LinkedHashSet<>(sourceArtifactIds)) {
            ProjectDocumentEntity artifact = document(session.getProject().getProjectKey(), id, actorId);
            ProjectDocumentRevisionEntity revision = artifact.getCurrentDraftRevision() != null ? artifact.getCurrentDraftRevision() : artifact.getPublishedRevision();
            contexts.save(DocumentAgentJobContextEntity.artifact(job, artifact, revision, ordinal++));
        }
        for (FeishuContext value : selectedFeishu) contexts.save(DocumentAgentJobContextEntity.feishu(job, value.docId(), value.docType(), value.title(), ordinal++));
        session.touch(Instant.now(), Instant.now().plusSeconds(30L * 24 * 60 * 60)); sessions.save(session);
        return jobView(job);
    }

    /** Dispatches durable jobs outside the user request transaction. */
    @Scheduled(fixedDelayString = "${skill-platform.document-agent.dispatch-delay-ms:1000}")
    public void dispatchPending() {
        for (DocumentAgentJobEntity job : jobs.findTop20ByStatusOrderByTimeCreatedAsc("QUEUED")) {
            try {
                job.claim(UUID.randomUUID().toString());
                jobs.saveAndFlush(job);
                AgentRunService.IssuedRun issued = runs.issueDocumentJobRun(job.getSession().getOwner().getId(), job.getSession().getProject().getProjectKey(), job.getSession().getDocument() == null ? null : job.getSession().getDocument().getId(), job.getSession().getProfileKey(), job.getSession().getSessionKey(), job.getJobKey());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("job_key", job.getJobKey()); body.put("session_key", job.getSession().getSessionKey());
                body.put("attempt_no", job.getDispatchAttempt()); body.put("profile_key", job.getSession().getProfileKey());
                body.put("instruction", runtimeInstruction(job, job.getInstruction())); body.put("mcp_token", issued.token());
                body.put("skill_snapshots", List.of()); body.put("context_manifest", Map.of("projectKey", job.getSession().getProject().getProjectKey(), "profileKey", job.getSession().getProfileKey()));
                body.put("idempotency_key", job.getJobKey() + ":" + job.getDispatchAttempt());
                Map<?, ?> result = gateway.post().uri("/internal/v1/document-jobs").header("X-SMS-Service-Token", serviceToken).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
                Map<?, ?> runtime = result == null || !(result.get("job") instanceof Map<?, ?> value) ? Map.of() : value;
                job.running(text(runtime, "job_id")); jobs.save(job);
            } catch (RuntimeException failure) {
                job.fail("GATEWAY_UNAVAILABLE", "Document Agent Gateway is unavailable", true); jobs.save(job);
            }
        }
    }

    public JobView getJob(String jobKey, Long actorId) { DocumentAgentJobEntity job = job(jobKey, actorId); syncEvents(job); return jobView(job); }

    public void cancelJob(String jobKey, Long actorId) {
        DocumentAgentJobEntity job = job(jobKey, actorId);
        if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(job.getStatus())) return;
        if (job.getRuntimeJobId() == null) { job.cancel(); jobs.save(job); return; }
        try { gateway.post().uri("/internal/v1/document-jobs/{id}/cancel", job.getRuntimeJobId()).header("X-SMS-Service-Token", serviceToken).retrieve().toBodilessEntity(); job.cancel(); jobs.save(job); }
        catch (RuntimeException failure) { job.fail("GATEWAY_UNAVAILABLE", "Gateway could not be cancelled", true); jobs.save(job); throw gatewayError(failure, "DOCUMENT_AGENT_GATEWAY_UNAVAILABLE"); }
    }

    public JobView retryJob(String jobKey, Long actorId) {
        DocumentAgentJobEntity job = job(jobKey, actorId);
        if (!"FAILED".equals(job.getStatus()) || !job.isRetryable()) throw error("DOCUMENT_AGENT_RETRY_NOT_ALLOWED", "This document job cannot be retried", HttpStatus.CONFLICT);
        job.retry(); jobs.save(job); return jobView(job);
    }

    public String events(String jobKey, Long actorId, long after) {
        DocumentAgentJobEntity job = job(jobKey, actorId); syncEvents(job);
        StringBuilder result = new StringBuilder();
        events.findByJobAndSequenceNoGreaterThanOrderBySequenceNoAsc(job, after).forEach(event -> result.append("id: ").append(event.getSequenceNo()).append("\nevent: ").append(event.getEventType()).append("\ndata: ").append(event.getPayload()).append("\n\n"));
        return result.toString();
    }

    @Transactional(readOnly = true)
    public JsonNode searchFeishu(Long actorId, String query, int limit, int offset) { return feishu.search(actorId, query, Math.min(Math.max(limit, 1), 50), Math.max(offset, 0)); }
    @Transactional(readOnly = true)
    public JsonNode resolveFeishu(Long actorId, String docId, String docType) { return feishu.fetch(actorId, docId, docType, 0, 1); }

    @Transactional(readOnly = true)
    public boolean allowsFeishu(String sessionKey, String docId, String docType) {
        DocumentAgentSessionEntity session = sessions.findForMcp(sessionKey).orElse(null);
        if (session == null || !"ACTIVE".equals(session.getStatus()) || session.getMcpTokenExpiresAt().isBefore(Instant.now())) return false;
        DocumentAgentJobEntity job = jobs.findFirstBySessionAndStatusInOrderBySequenceNoDesc(session, List.of("RUNNING")).orElse(null);
        return job != null && contexts.existsByJobAndContextKindAndFeishuDocIdAndFeishuDocType(job, "FEISHU_DOCUMENT", docId, docType);
    }

    private void syncEvents(DocumentAgentJobEntity job) {
        if (job.getRuntimeJobId() == null) return;
        long after = events.findTopByJobOrderBySequenceNoDesc(job).map(DocumentAgentJobEventEntity::getSequenceNo).orElse(0L);
        try {
            String stream = gateway.get().uri(uri -> uri.path("/internal/v1/document-jobs/{id}/events").queryParam("last_event_id", after).build(job.getRuntimeJobId())).header("X-SMS-Service-Token", serviceToken).retrieve().body(String.class);
            if (stream != null) parseEvents(job, stream, after);
            Map<?, ?> runtime = gateway.get().uri("/internal/v1/document-jobs/{id}", job.getRuntimeJobId()).header("X-SMS-Service-Token", serviceToken).retrieve().body(Map.class);
            applyRuntimeStatus(job, runtime == null ? null : text(runtime, "status")); jobs.save(job);
        } catch (RuntimeException ignored) { /* The job remains queryable and retryable. */ }
    }

    private void parseEvents(DocumentAgentJobEntity job, String stream, long after) {
        String eventType = null; String data = null; long sequence = after;
        for (String line : stream.split("\\R")) {
            if (line.startsWith("id: ")) sequence = Long.parseLong(line.substring(4).trim());
            else if (line.startsWith("event: ")) eventType = line.substring(7).trim();
            else if (line.startsWith("data: ")) data = line.substring(6);
            else if (line.isBlank() && eventType != null && data != null) {
                boolean duplicate = false;
                for (DocumentAgentJobEventEntity value : events.findByJobAndSequenceNoGreaterThanOrderBySequenceNoAsc(job, sequence - 1)) {
                    if (value.getSequenceNo() == sequence) { duplicate = true; break; }
                }
                if (!duplicate) {
                    events.save(new DocumentAgentJobEventEntity(job, sequence, null, eventType, data, Instant.now()));
                    if ("artifact.saved".equals(eventType)) {
                        try {
                            JsonNode node = mapper.readTree(data);
                            JsonNode payload = node.path("data");
                            JsonNode artifact = payload.path("artifact").isObject() ? payload.path("artifact") : payload;
                            long artifactId = artifact.has("artifactId") ? artifact.path("artifactId").asLong(0L) : artifact.path("id").asLong(0L);
                            long revisionId = artifact.has("revisionId") ? artifact.path("revisionId").asLong(0L) : artifact.path("currentDraftRevisionId").asLong(0L);
                            if (revisionId == 0L) revisionId = artifact.path("draft").path("id").asLong(0L);
                            if (artifactId > 0 && revisionId > 0) job.artifact(artifactId, revisionId, artifact.path("documentUrl").asText(null));
                        } catch (Exception ignored) { }
                    }
                }
                eventType = null; data = null;
            }
        }
    }

    private void applyRuntimeStatus(DocumentAgentJobEntity job, String status) {
        if (status == null) return;
        switch (status) { case "COMPLETED" -> { if (job.getArtifactId() != null && job.getRevisionId() != null) job.complete(); else job.fail("ARTIFACT_NOT_SAVED", "OpenHands completed without saving a document artifact", false); } case "FAILED" -> job.fail("OPENHANDS_JOB_FAILED", "OpenHands job failed", true); case "CANCELLED" -> job.cancel(); default -> { } }
    }
    private String runtimeInstruction(DocumentAgentJobEntity job, String instruction) {
        StringBuilder context = new StringBuilder();
        context.append("\n\n[SMS document-agent context]\n");
        context.append("This is a document workflow turn. Use the project MCP tools and save the resulting draft with save_artifact_draft. ");
        if (job.getSession().getDocument() != null) context.append("Continue target artifact id=").append(job.getSession().getDocument().getId()).append(". ");
        context.append("Profile=").append(job.getSession().getProfileKey()).append(". ");
        for (DocumentAgentJobContextEntity value : contexts.findByJobOrderByOrdinalNoAsc(job)) {
            if ("FEISHU_DOCUMENT".equals(value.getContextKind())) context.append("Selected Feishu document: docId=").append(value.getFeishuDocId()).append(", docType=").append(value.getFeishuDocType()).append(", title=").append(value.getFeishuTitle()).append(". ");
            else if (value.getArtifact() != null) context.append("Selected project artifact id=").append(value.getArtifact().getId()).append(". ");
        }
        return context.append("\nUser instruction:\n").append(instruction).toString();
    }
    private DocumentAgentSessionEntity access(String key, Long actorId) { return sessions.findBySessionKeyAndOwnerId(key, actorId).orElseThrow(() -> error("DOCUMENT_AGENT_SESSION_NOT_FOUND", "Document agent session not found", HttpStatus.NOT_FOUND)); }
    private DocumentAgentJobEntity job(String key, Long actorId) { DocumentAgentJobEntity job = jobs.findByJobKey(key).orElseThrow(() -> error("DOCUMENT_AGENT_JOB_NOT_FOUND", "Document agent job not found", HttpStatus.NOT_FOUND)); access(job.getSession().getSessionKey(), actorId); return job; }
    private ProjectDocumentEntity document(String projectKey, Long documentId, Long actorId) { return documents.findByIdAndProjectId(documentId, projects.projectIdForAgent(projectKey, actorId)).orElseThrow(() -> error("PROJECT_DOCUMENT_NOT_FOUND", "Project document not found", HttpStatus.NOT_FOUND)); }
    private VirtualProjectEntity projectEntity(String key, Long actorId) { projects.assertAgentAccess(key, actorId); return virtualProjects.findByProjectKey(key).orElseThrow(() -> error("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND)); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> error("USER_NOT_FOUND", "User not found", HttpStatus.UNAUTHORIZED)); }
    private void requireProfile(String profile) { if (!PROFILES.contains(profile)) throw error("AGENT_PROFILE_INVALID", "Unsupported document agent profile", HttpStatus.BAD_REQUEST); }
    private SessionView sessionView(DocumentAgentSessionEntity value) { return new SessionView(value.getSessionKey(), value.getProject().getProjectKey(), value.getOwner().getId(), value.getProfileKey(), value.getDocument() == null ? null : value.getDocument().getId(), value.getTargetTitle(), value.getStatus(), value.getRuntimeSessionId(), value.getConversationId(), value.getWorkspaceId(), value.getLastActivityAt(), value.getClosedAt()); }
    private JobView jobView(DocumentAgentJobEntity value) { return new JobView(value.getJobKey(), value.getSession().getSessionKey(), value.getSequenceNo(), value.getStatus(), value.getRuntimeJobId(), value.getErrorCode(), value.getErrorMessage(), value.isRetryable(), value.getStartedAt(), value.getFinishedAt(), value.getArtifactId(), value.getRevisionId(), value.getDocumentUrl()); }
    private String text(Map<?, ?> map, String key) { Object value = map.get(key); return value == null ? null : String.valueOf(value); }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private BusinessException gatewayError(RuntimeException failure, String code) { if (failure instanceof RestClientResponseException) return error(code, "Document Agent Gateway request failed", HttpStatus.BAD_GATEWAY); return error(code, "Document Agent Gateway is unavailable", HttpStatus.BAD_GATEWAY); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
    public record FeishuContext(String docId, String docType, String title) {}
    public record SessionView(String sessionKey, String projectKey, Long ownerId, String profileKey, Long documentId, String targetTitle, String status, String runtimeSessionId, String conversationId, String workspaceId, Instant lastActivityAt, Instant closedAt) {}
    public record JobView(String jobKey, String sessionKey, int sequenceNo, String status, String runtimeJobId, String errorCode, String errorMessage, boolean retryable, Instant startedAt, Instant finishedAt, Long artifactId, Long revisionId, String documentUrl) {}
}
