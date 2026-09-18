package com.company.skillplatform.project.application;

import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.agent.infrastructure.FeishuDocumentMcpProxy;
import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.project.infrastructure.entity.*;
import com.company.skillplatform.project.infrastructure.repository.*;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SMS-owned document-agent sessions. DSH sessions never enter this service. */
@Service
public class DocumentAgentSessionService {
    private static final Logger log = LoggerFactory.getLogger(DocumentAgentSessionService.class);
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
    private final int maxDispatchAttempts;
    private final ProjectWorkflowService workflow;
    private final DocumentAgentSessionWikiContextRepository sessionWikiContexts;
    private final WikiDocumentService wiki;
    private final AuditService audit;

    public DocumentAgentSessionService(ProjectControlService projects, ProjectDocumentRepository documents,
                                       VirtualProjectRepository virtualProjects, IamUserRepository users,
                                       DocumentAgentSessionRepository sessions, DocumentAgentJobRepository jobs,
                                       DocumentAgentJobContextRepository contexts, DocumentAgentJobEventRepository events,
                                       AgentRunService runs, FeishuDocumentMcpProxy feishu, ObjectMapper mapper, ProjectWorkflowService workflow,
                                       DocumentAgentSessionWikiContextRepository sessionWikiContexts, WikiDocumentService wiki, AuditService audit,
                                       RestClient.Builder builder,
                                       @Value("${skill-platform.document-agent.gateway-url:http://127.0.0.1:8090}") String gatewayUrl,
                                       @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String serviceToken,
                                       @Value("${skill-platform.document-agent.max-dispatch-attempts:3}") int maxDispatchAttempts) {
        this.projects = projects; this.documents = documents; this.virtualProjects = virtualProjects; this.users = users; this.sessions = sessions; this.jobs = jobs;
        this.contexts = contexts; this.events = events; this.runs = runs; this.feishu = feishu; this.mapper = mapper;
        this.workflow = workflow;
        this.sessionWikiContexts = sessionWikiContexts; this.wiki = wiki; this.audit = audit;
        this.gateway = builder.baseUrl(gatewayUrl).build(); this.serviceToken = serviceToken; this.maxDispatchAttempts = Math.max(1, maxDispatchAttempts);
    }

    @Transactional(readOnly = true)
    public List<SessionView> list(String projectKey, Long actorId, int limit) {
        List<DocumentAgentSessionEntity> values = projectKey == null || projectKey.isBlank()
                ? sessions.findByOwnerIdOrderByLastActivityAtDesc(actorId, org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 50)))
                : sessions.findByProjectProjectKeyAndOwnerIdOrderByLastActivityAtDesc(projectKey, actorId, org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 50)));
        return values.stream().map(this::sessionView).toList();
    }

    public SessionView create(String projectKey, Long actorId, String profileKey, String mode, Long documentId, String title, String idempotencyKey) {
        return create(projectKey, actorId, profileKey, mode, documentId, title, idempotencyKey, null);
    }

    public SessionView create(String projectKey, Long actorId, String profileKey, String mode, Long documentId, String title, String idempotencyKey, String stageKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw error("IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key is required", HttpStatus.BAD_REQUEST);
        ProjectWorkflowService.AgentStageContext stageContext = stageKey == null ? null : workflow.agentContext(projectKey, stageKey.toUpperCase(Locale.ROOT), actorId);
        if (stageContext != null) profileKey = stageContext.profileKey();
        if (stageContext != null) {
            DocumentAgentSessionEntity shared = sessions.findFirstByProjectProjectKeyAndStageIdAndStatusOrderByTimeCreatedDesc(projectKey, stageContext.stageId(), "ACTIVE").orElse(null);
            if (shared != null) return sessionView(shared);
        }
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
        if (stageContext != null) session.bindStage(stageContext.stageId());
        sessions.saveAndFlush(session);
        AgentRunService.IssuedRun issued = runs.issueDocumentSessionRun(actorId, projectKey, documentId, profileKey, session.getSessionKey());
        session.setMcpTokenHash(sha256(issued.token()));
        session.activateLocally();
        sessions.saveAndFlush(session);
        return sessionView(session);
    }

    @Transactional
    public SessionView get(String sessionKey, Long actorId) { return sessionView(access(sessionKey, actorId)); }

    public void close(String sessionKey, Long actorId) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        if ("CLOSED".equals(session.getStatus()) || "EXPIRED".equals(session.getStatus())) return;
        DocumentAgentJobEntity active = jobs.findFirstBySessionAndStatusInOrderBySequenceNoDesc(session, List.of("QUEUED", "DISPATCHING", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED")).orElse(null);
        if (active != null && active.getRuntimeJobId() != null) {
            try { gateway.post().uri("/internal/v1/document-jobs/{id}/cancel", active.getRuntimeJobId()).header("X-SMS-Service-Token", serviceToken).retrieve().toBodilessEntity(); } catch (RuntimeException ignored) { active.fail("GATEWAY_UNAVAILABLE", "Gateway could not be cancelled", true); jobs.save(active); }
        }
        try { gateway.delete().uri("/internal/v1/document-sessions/{id}", session.getSessionKey()).header("X-SMS-Service-Token", serviceToken).retrieve().toBodilessEntity(); } catch (RuntimeException ignored) { /* local close remains authoritative */ }
        session.close(Instant.now()); sessions.save(session);
    }

    @Transactional
    public JobView turn(String sessionKey, Long actorId, String instruction, String idempotencyKey,
                        List<Long> sourceArtifactIds, List<FeishuContext> feishuDocuments,
                        String turnMode, Long targetDocumentId, String targetTitle) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        if (!"ACTIVE".equals(session.getStatus())) throw error("DOCUMENT_AGENT_SESSION_CLOSED", "Document agent session is not active", HttpStatus.CONFLICT);
        String mode = turnMode == null || turnMode.isBlank() ? "DISCUSS" : turnMode.toUpperCase(Locale.ROOT);
        if (!Set.of("DISCUSS","CREATE_ARTIFACT","UPDATE_ARTIFACT").contains(mode)) throw error("DOCUMENT_AGENT_TURN_MODE_INVALID", "Unsupported turn mode", HttpStatus.BAD_REQUEST);
        if (session.getStageId() != null) workflow.assertAgentWritable(session.getStageId(), actorId);
        if ("CREATE_ARTIFACT".equals(mode) && (targetTitle == null || targetTitle.isBlank())) throw error("DOCUMENT_AGENT_TITLE_REQUIRED", "A title is required", HttpStatus.BAD_REQUEST);
        if ("UPDATE_ARTIFACT".equals(mode)) {
            if (targetDocumentId == null) throw error("DOCUMENT_AGENT_TARGET_REQUIRED", "A target document is required", HttpStatus.BAD_REQUEST);
            if (session.getStageId() != null) workflow.assertStageArtifact(session.getStageId(), targetDocumentId);
        }
        DocumentAgentJobEntity existing = jobs.findBySessionAndIdempotencyKey(session, idempotencyKey).orElse(null);
        if (existing != null) return jobView(existing);
        jobs.findFirstBySessionAndStatusInOrderBySequenceNoDesc(session, List.of("QUEUED", "DISPATCHING", "RUNNING", "RETRY_WAIT", "CANCEL_REQUESTED")).ifPresent(value -> { throw error("DOCUMENT_AGENT_JOB_ACTIVE", "A document job is already active", HttpStatus.CONFLICT); });
        List<FeishuContext> selectedFeishu = feishuDocuments == null ? List.of() : feishuDocuments.stream().filter(Objects::nonNull).distinct().toList();
        if (selectedFeishu.size() > 10) throw error("DOCUMENT_AGENT_CONTEXT_LIMIT", "At most 10 Feishu documents may be selected", HttpStatus.BAD_REQUEST);
        int next = jobs.findTopBySessionOrderBySequenceNoDesc(session).map(value -> value.getSequenceNo() + 1).orElse(1);
        DocumentAgentJobEntity job = jobs.saveAndFlush(new DocumentAgentJobEntity(session, user(actorId), next, idempotencyKey, instruction, mode, targetDocumentId, targetTitle));
        int ordinal = 0;
        if (sourceArtifactIds != null) for (Long id : new LinkedHashSet<>(sourceArtifactIds)) {
            ProjectDocumentEntity artifact = document(session.getProject().getProjectKey(), id, actorId);
            ProjectDocumentRevisionEntity revision = artifact.getCurrentDraftRevision() != null ? artifact.getCurrentDraftRevision() : artifact.getPublishedRevision();
            contexts.save(DocumentAgentJobContextEntity.artifact(job, artifact, revision, ordinal++));
        }
        for (FeishuContext value : selectedFeishu) contexts.save(DocumentAgentJobContextEntity.feishu(job, value.docId(), value.docType(), value.title(), ordinal++));
        for (DocumentAgentSessionWikiContextEntity value : sessionWikiContexts.findBySessionOrderByOrdinalNoAsc(session))
            contexts.save(DocumentAgentJobContextEntity.wiki(job, value.getWikiDocument(), ordinal++));
        session.touch(Instant.now(), Instant.now().plusSeconds(30L * 24 * 60 * 60)); sessions.save(session);
        return jobView(job);
    }

    @Transactional(readOnly = true)
    public WikiContextList wikiContexts(String sessionKey, Long actorId) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        return new WikiContextList(sessionWikiContexts.findBySessionOrderByOrdinalNoAsc(session).stream().map(this::wikiContextView).toList(), 10);
    }

    @Transactional
    public WikiContextList replaceWikiContexts(String sessionKey, Long actorId, List<Long> documentIds, String requestId) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        if (session.getStageId() == null) throw error("DOCUMENT_AGENT_STAGE_REQUIRED", "Wiki context sharing is only available for project stages", HttpStatus.CONFLICT);
        workflow.assertAgentWritable(session.getStageId(), actorId);
        List<Long> ids = documentIds == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(documentIds));
        if (ids.size() > 10) throw error("DOCUMENT_AGENT_WIKI_CONTEXT_LIMIT", "At most 10 platform Wiki documents may be selected", HttpStatus.BAD_REQUEST);
        List<WikiDocumentEntity> selected = ids.stream().map(id -> wiki.selectableForDocumentAgent(id, actorId)).toList();
        List<Long> before = sessionWikiContexts.findBySessionOrderByOrdinalNoAsc(session).stream().map(v -> v.getWikiDocument().getId()).toList();
        sessionWikiContexts.deleteBySession(session);
        sessionWikiContexts.flush();
        IamUserEntity actor = user(actorId);
        for (int i = 0; i < selected.size(); i++) sessionWikiContexts.save(new DocumentAgentSessionWikiContextEntity(session, selected.get(i), actor, i));
        audit.success("DOCUMENT_AGENT_WIKI_CONTEXTS_REPLACED", actor, "DOCUMENT_AGENT_SESSION", session.getId(), requestId,
                Map.of("documentIds", before), Map.of("documentIds", ids), Map.of("projectKey", session.getProject().getProjectKey(), "stageId", session.getStageId()));
        return wikiContexts(sessionKey, actorId);
    }

    @Transactional(readOnly = true)
    public List<WikiContextView> searchSelectedWiki(String sessionKey, String jobKey, String keyword) {
        DocumentAgentJobEntity job = activeMcpJob(sessionKey, jobKey);
        String term = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return contexts.findByJobOrderByOrdinalNoAsc(job).stream().filter(v -> "WIKI_DOCUMENT".equals(v.getContextKind()) && v.getWikiDocument() != null)
                .map(v -> new WikiContextView(v.getWikiDocument().getId(), v.getWikiDocument().getTitle(), v.getWikiDocument().getDocumentType(), v.getWikiDocument().getCurrentRevision() == null ? 0 : v.getWikiDocument().getCurrentRevision().getRevisionNo()))
                .filter(v -> term.isBlank() || v.title().toLowerCase(Locale.ROOT).contains(term)).toList();
    }

    @Transactional(readOnly = true)
    public WikiReadView readSelectedWiki(String sessionKey, String jobKey, Long documentId, int offset, int maxChars) {
        DocumentAgentJobEntity job = activeMcpJob(sessionKey, jobKey);
        if (!contexts.existsByJobAndContextKindAndWikiDocumentId(job, "WIKI_DOCUMENT", documentId))
            throw error("DOCUMENT_AGENT_CONTEXT_DENIED", "The Wiki document was not selected for the active document job", HttpStatus.FORBIDDEN);
        var document = wiki.readProjectSharedForDocumentAgent(documentId);
        int start = Math.max(0, offset), size = Math.min(Math.max(maxChars, 1), 64 * 1024);
        String text = document.markdownContent();
        String content = start >= text.length() ? "" : text.substring(start, Math.min(text.length(), start + size));
        return new WikiReadView(document.id(), document.title(), document.documentType(), document.revisionNo(), content, start, start + content.length() < text.length());
    }

    private DocumentAgentJobEntity activeMcpJob(String sessionKey, String jobKey) {
        DocumentAgentJobEntity job = jobs.findByJobKey(jobKey).orElseThrow(() -> error("DOCUMENT_AGENT_CONTEXT_DENIED", "Document agent context is unavailable", HttpStatus.FORBIDDEN));
        if (!job.getSession().getSessionKey().equals(sessionKey) || !Set.of("DISPATCHING", "RUNNING").contains(job.getStatus()))
            throw error("DOCUMENT_AGENT_CONTEXT_DENIED", "Document agent context is unavailable", HttpStatus.FORBIDDEN);
        return job;
    }

    /** Dispatches durable jobs outside the user request transaction. */
    @Scheduled(fixedDelayString = "${skill-platform.document-agent.dispatch-delay-ms:1000}")
    public void dispatchPending() {
        for (DocumentAgentJobEntity job : jobs.findDispatchable(Instant.now(), org.springframework.data.domain.PageRequest.of(0, 20))) {
            try {
                job.claim(UUID.randomUUID().toString());
                // findDispatchable runs without a surrounding transaction, so saveAndFlush uses merge.
                // Continue with the managed copy carrying the incremented optimistic-lock version.
                job = jobs.saveAndFlush(job);
                // merge may replace the eagerly loaded session graph with a detached lazy proxy.
                // Reload through the repository EntityGraph before building the gateway request.
                job = jobs.findByJobKey(job.getJobKey()).orElseThrow();
                ensureRuntimeSession(job.getSession());
                AgentRunService.IssuedRun issued = runs.issueDocumentJobRun(job.getRequestedBy().getId(), job.getSession().getProject().getProjectKey(), job.getSession().getDocument() == null ? null : job.getSession().getDocument().getId(), job.getSession().getProfileKey(), job.getSession().getSessionKey(), job.getJobKey());
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("job_key", job.getJobKey()); body.put("session_key", job.getSession().getSessionKey());
                body.put("attempt_no", job.getDispatchAttempt()); body.put("profile_key", job.getSession().getProfileKey());
                body.put("instruction", runtimeInstruction(job, job.getInstruction())); body.put("mcp_token", issued.token());
                body.put("turn_mode", job.getTurnMode()); body.put("target_document_id", job.getTargetDocumentId() == null ? null : String.valueOf(job.getTargetDocumentId())); body.put("target_title", job.getTargetTitle());
                List<Map<String,Object>> snapshots = skillSnapshots(job.getSession());
                body.put("skill_snapshots", snapshots); body.put("context_manifest", Map.of("projectKey", job.getSession().getProject().getProjectKey(), "profileKey", job.getSession().getProfileKey(), "stageId", job.getSession().getStageId() == null ? "" : job.getSession().getStageId()));
                body.put("idempotency_key", job.getJobKey() + ":" + job.getDispatchAttempt());
                Map<?, ?> result;
                try {
                    result = submitJob(body);
                } catch (RestClientResponseException missingRuntimeSession) {
                    // Gateway state may be restored from a different persistent store after a restart.
                    // Rebind the SMS session once when the old runtime session no longer exists.
                    if (missingRuntimeSession.getStatusCode().value() != 404 ||
                            !String.valueOf(missingRuntimeSession.getResponseBodyAsString()).contains("DOCUMENT_SESSION_NOT_FOUND")) throw missingRuntimeSession;
                    job.getSession().runtimeUnbound();
                    sessions.save(job.getSession());
                    ensureRuntimeSession(job.getSession());
                    body.put("session_key", job.getSession().getSessionKey());
                    result = submitJob(body);
                }
                Map<?, ?> runtime = result == null || !(result.get("job") instanceof Map<?, ?> value) ? Map.of() : value;
                job.running(text(runtime, "job_id")); jobs.save(job);
            } catch (RuntimeException failure) {
                log.warn("event=document_agent.dispatch.failed jobKey={} attempt={} failureType={} message={}",
                        job.getJobKey(), job.getDispatchAttempt(), failure.getClass().getSimpleName(), failure.getMessage());
                if (job.getDispatchAttempt() < maxDispatchAttempts) {
                    long delaySeconds = Math.min(300L, 1L << Math.min(job.getDispatchAttempt(), 8));
                    job.retryWaiting("GATEWAY_UNAVAILABLE", "Document Agent Gateway is unavailable", Instant.now().plusSeconds(delaySeconds));
                } else {
                    job.fail("GATEWAY_UNAVAILABLE", "Document Agent Gateway is unavailable", true);
                }
                jobs.save(job);
            }
        }
    }

    private Map<?, ?> submitJob(Map<String, Object> body) {
        return gateway.post().uri("/internal/v1/document-jobs").header("X-SMS-Service-Token", serviceToken)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
    }

    private void ensureRuntimeSession(DocumentAgentSessionEntity session) {
        if (session.getRuntimeSessionId() != null) return;
        AgentRunService.IssuedRun issued = runs.issueDocumentSessionRun(session.getOwner().getId(), session.getProject().getProjectKey(), session.getDocument() == null ? null : session.getDocument().getId(), session.getProfileKey(), session.getSessionKey());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", session.getSessionKey()); body.put("actor_id", String.valueOf(session.getOwner().getId())); body.put("project_id", session.getProject().getProjectKey());
        body.put("profile_key", session.getProfileKey()); body.put("document_id", session.getDocument() == null ? null : String.valueOf(session.getDocument().getId()));
        if (session.getStageId() != null) {
            ProjectWorkflowService.AgentStageContext context = workflow.agentContext(session.getStageId(), session.getOwner().getId());
            body.put("managed_skills", context.skills().stream().map(s -> Map.of("name",s.skillKey(),"content",s.content())).toList());
            body.put("skill_snapshots", skillSnapshots(session));
        }
        body.put("mcp_token", issued.token()); body.put("idempotency_key", session.getIdempotencyKey());
        Map<?, ?> result = gateway.post().uri("/internal/v1/document-sessions").header("X-SMS-Service-Token", serviceToken).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
        Map<?, ?> runtime = result == null ? Map.of() : result;
        session.runtimeBound(text(runtime, "session_id"), text(runtime, "conversation_id"), text(runtime, "workspace_id"));
        sessions.save(session);
    }

    public JobView getJob(String jobKey, Long actorId) { DocumentAgentJobEntity job = job(jobKey, actorId); syncEvents(job); return jobView(job); }

    public List<MessageView> messages(String sessionKey, Long actorId) {
        DocumentAgentSessionEntity session = access(sessionKey, actorId);
        List<DocumentAgentJobEntity> history = jobs.findBySessionOrderBySequenceNoAsc(session);
        history.stream().filter(j -> Set.of("RUNNING","DISPATCHING").contains(j.getStatus())).forEach(this::syncEvents);
        return history.stream().map(job -> {
            StringBuilder assistant = new StringBuilder();
            for (DocumentAgentJobEventEntity event : events.findByJobAndSequenceNoGreaterThanOrderBySequenceNoAsc(job, 0)) {
                if (!"message.delta".equals(event.getEventType())) continue;
                try {
                    JsonNode node=mapper.readTree(event.getPayload()); String value=node.path("data").path("text").asText("");
                    if(!value.isBlank()) assistant.append(value);
                } catch (Exception ignored) { }
            }
            IamUserEntity requester=job.getRequestedBy();
            return new MessageView(job.getJobKey(),job.getSequenceNo(),job.getTurnMode(),job.getInstruction(),requester.getId(),requester.getDisplayName(),assistant.toString(),job.getStatus(),job.getErrorCode(),job.getErrorMessage(),job.getArtifactId(),job.getRevisionId(),job.getTimeCreated(),job.getFinishedAt());
        }).toList();
    }

    /** Pulls runtime events independently of browser polling so terminal state converges after a reload. */
    @Scheduled(fixedDelayString = "${skill-platform.document-agent.event-collector-delay-ms:1000}")
    public void collectRuntimeEvents() {
        for (DocumentAgentJobEntity job : jobs.findTop20ByStatusOrderByTimeCreatedAsc("RUNNING")) syncEvents(job);
    }

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

    @Transactional
    public ProjectControlService.DocumentView saveAgentDraft(com.company.skillplatform.agent.domain.AgentRun agent,
                                                              String artifactType, String title, String content,
                                                              Long artifactId, Integer versionNo, Object skillSnapshots,
                                                              Object assumptions, Object openQuestions, List<Long> sourceArtifactIds) {
        if (agent.sessionKey() == null || agent.projectKey() == null) throw error("DOCUMENT_JOB_CONTEXT_REQUIRED", "A document job token is required", HttpStatus.FORBIDDEN);
        DocumentAgentSessionEntity session = sessions.findForMcp(agent.sessionKey()).orElseThrow(() -> error("DOCUMENT_AGENT_SESSION_NOT_FOUND", "Document agent session not found", HttpStatus.NOT_FOUND));
        DocumentAgentJobEntity job = jobs.findByJobKey(agent.runRef()).orElseThrow(() -> error("DOCUMENT_AGENT_JOB_NOT_FOUND", "Document agent job not found", HttpStatus.NOT_FOUND));
        if (!job.getSession().getSessionKey().equals(session.getSessionKey()) || !Set.of("RUNNING", "DISPATCHING").contains(job.getStatus())) throw error("DOCUMENT_AGENT_JOB_NOT_ACTIVE", "Document job is not active", HttpStatus.CONFLICT);
        if (job.getArtifactId() != null) return projects.getDocument(agent.projectKey(), job.getArtifactId(), agent.userId());
        if ("DISCUSS".equals(job.getTurnMode())) throw error("DOCUMENT_AGENT_DISCUSSION_CANNOT_SAVE", "Discussion turns cannot save documents", HttpStatus.CONFLICT);
        String expectedType = DOCUMENT_TYPES.get(session.getProfileKey());
        if (!expectedType.equalsIgnoreCase(artifactType)) throw error("DOCUMENT_AGENT_PROFILE_TARGET_MISMATCH", "Artifact type does not match the selected profile", HttpStatus.BAD_REQUEST);
        ProjectControlService.DocumentView saved;
        if ("CREATE_ARTIFACT".equals(job.getTurnMode())) {
            if (artifactId != null) throw error("DOCUMENT_AGENT_CREATE_REQUIRES_NEW", "Create turns must create a new document", HttpStatus.CONFLICT);
            String effectiveTitle = job.getTargetTitle() == null ? title : job.getTargetTitle();
            saved = projects.createAgentDocument(agent.projectKey(), new ProjectControlService.AgentDocument(expectedType, effectiveTitle, content, session.getProfileKey(), session.getSessionKey(), job.getJobKey(), skillSnapshots, assumptions, openQuestions, sourceArtifactIds), agent.userId(), "mcp:" + agent.runRef());
            if (session.getStageId() == null) { session.bindDocument(documents.findById(saved.id()).orElseThrow()); sessions.save(session); }
        } else {
            Long target = job.getTargetDocumentId() != null ? job.getTargetDocumentId() : artifactId;
            if (artifactId == null || !artifactId.equals(target)) throw error("DOCUMENT_AGENT_TARGET_FORBIDDEN", "Artifact is not the turn target", HttpStatus.FORBIDDEN);
            ProjectDocumentEntity targetDocument = documents.findById(artifactId).orElseThrow();
            if (session.getStageId() == null && (session.getDocument() == null || !session.getDocument().getId().equals(artifactId))) throw error("DOCUMENT_AGENT_TARGET_FORBIDDEN", "Artifact is not the session target", HttpStatus.FORBIDDEN);
            int currentVersion = versionNo == null ? targetDocument.getVersionNo() : versionNo;
            saved = projects.saveDraft(agent.projectKey(), artifactId, new ProjectControlService.SaveDraft(title, content, currentVersion, "AGENT", session.getProfileKey(), session.getSessionKey(), job.getJobKey(), skillSnapshots, assumptions, openQuestions, sourceArtifactIds), agent.userId(), "mcp:" + agent.runRef());
        }
        Long revisionId = saved.draft() == null ? null : saved.draft().id();
        job.artifact(saved.id(), revisionId, "/projects/" + saved.projectKey() + "/documents/" + saved.id());
        jobs.save(job);
        if (session.getStageId() != null) workflow.linkArtifact(session.getStageId(), saved.id(), job.getId(), agent.userId());
        return saved;
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
        } catch (RuntimeException failure) {
            log.warn("event=document_agent.events.sync_failed jobKey={} runtimeJobId={} failureType={} message={}",
                    job.getJobKey(), job.getRuntimeJobId(), failure.getClass().getSimpleName(), failure.getMessage());
            /* The job remains queryable and retryable. */
        }
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
        switch (status) { case "COMPLETED" -> { if ("DISCUSS".equals(job.getTurnMode()) || (job.getArtifactId() != null && job.getRevisionId() != null)) job.complete(); else job.fail("ARTIFACT_NOT_SAVED", "OpenHands completed without saving a document artifact", false); } case "FAILED" -> job.fail("OPENHANDS_JOB_FAILED", "OpenHands job failed", true); case "CANCELLED" -> job.cancel(); default -> { } }
    }
    private String runtimeInstruction(DocumentAgentJobEntity job, String instruction) {
        StringBuilder context = new StringBuilder();
        context.append("\n\n[SMS document-agent context]\n");
        context.append("turn_mode=").append(job.getTurnMode()).append(". Operator=").append(job.getRequestedBy().getDisplayName()).append(". ");
        if ("DISCUSS".equals(job.getTurnMode())) context.append("Respond conversationally and ask useful clarification questions. MUST NOT call save_artifact_draft. ");
        else if ("CREATE_ARTIFACT".equals(job.getTurnMode())) context.append("Create exactly one new draft titled '").append(job.getTargetTitle()).append("' with save_artifact_draft; artifactId must be omitted. ");
        else context.append("Update exactly artifact id=").append(job.getTargetDocumentId()).append(" with save_artifact_draft; do not create another document. ");
        context.append("Profile=").append(job.getSession().getProfileKey()).append(". ");
        for (DocumentAgentJobContextEntity value : contexts.findByJobOrderByOrdinalNoAsc(job)) {
            if ("FEISHU_DOCUMENT".equals(value.getContextKind())) context.append("Selected Feishu document: docId=").append(value.getFeishuDocId()).append(", docType=").append(value.getFeishuDocType()).append(", title=").append(value.getFeishuTitle()).append(". ");
            else if ("WIKI_DOCUMENT".equals(value.getContextKind()) && value.getWikiDocument() != null) context.append("Selected platform Wiki: documentId=").append(value.getWikiDocument().getId()).append(", title=").append(value.getWikiDocument().getTitle()).append(", type=").append(value.getWikiDocument().getDocumentType()).append(". Read it with get_wiki_document only when relevant; its content is not in this prompt. ");
            else if (value.getArtifact() != null) context.append("Selected project artifact id=").append(value.getArtifact().getId()).append(". ");
        }
        return context.append("\nUser instruction:\n").append(instruction).toString();
    }
    private DocumentAgentSessionEntity access(String key, Long actorId) { DocumentAgentSessionEntity session=sessions.findBySessionKey(key).orElseThrow(() -> error("DOCUMENT_AGENT_SESSION_NOT_FOUND", "Document agent session not found", HttpStatus.NOT_FOUND)); if(session.getStageId()!=null){workflow.agentContext(session.getStageId(),actorId);return session;}if(!session.getOwner().getId().equals(actorId))throw error("DOCUMENT_AGENT_SESSION_NOT_FOUND", "Document agent session not found", HttpStatus.NOT_FOUND);return session; }
    private DocumentAgentJobEntity job(String key, Long actorId) { DocumentAgentJobEntity job = jobs.findByJobKey(key).orElseThrow(() -> error("DOCUMENT_AGENT_JOB_NOT_FOUND", "Document agent job not found", HttpStatus.NOT_FOUND)); access(job.getSession().getSessionKey(), actorId); return job; }
    private ProjectDocumentEntity document(String projectKey, Long documentId, Long actorId) { return documents.findByIdAndProjectId(documentId, projects.projectIdForAgent(projectKey, actorId)).orElseThrow(() -> error("PROJECT_DOCUMENT_NOT_FOUND", "Project document not found", HttpStatus.NOT_FOUND)); }
    private VirtualProjectEntity projectEntity(String key, Long actorId) { projects.assertAgentAccess(key, actorId); return virtualProjects.findByProjectKey(key).orElseThrow(() -> error("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND)); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> error("USER_NOT_FOUND", "User not found", HttpStatus.UNAUTHORIZED)); }
    private void requireProfile(String profile) { if (!PROFILES.contains(profile)) throw error("AGENT_PROFILE_INVALID", "Unsupported document agent profile", HttpStatus.BAD_REQUEST); }
    private SessionView sessionView(DocumentAgentSessionEntity value) { return new SessionView(value.getSessionKey(), value.getProject().getProjectKey(), value.getOwner().getId(), value.getProfileKey(), value.getDocument() == null ? null : value.getDocument().getId(), value.getTargetTitle(), value.getStatus(), value.getRuntimeSessionId(), value.getConversationId(), value.getWorkspaceId(), value.getLastActivityAt(), value.getClosedAt(), value.getStageId()); }
    private JobView jobView(DocumentAgentJobEntity value) { return new JobView(value.getJobKey(), value.getSession().getSessionKey(), value.getSequenceNo(), value.getStatus(), value.getRuntimeJobId(), value.getErrorCode(), value.getErrorMessage(), value.isRetryable(), value.getStartedAt(), value.getFinishedAt(), value.getArtifactId(), value.getRevisionId(), value.getDocumentUrl(), value.getTurnMode(), value.getTargetDocumentId(), value.getTargetTitle()); }
    private String text(Map<?, ?> map, String key) { Object value = map.get(key); return value == null ? null : String.valueOf(value); }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private BusinessException gatewayError(RuntimeException failure, String code) { if (failure instanceof RestClientResponseException) return error(code, "Document Agent Gateway request failed", HttpStatus.BAD_GATEWAY); return error(code, "Document Agent Gateway is unavailable", HttpStatus.BAD_GATEWAY); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
    public record FeishuContext(String docId, String docType, String title) {}
    private WikiContextView wikiContextView(DocumentAgentSessionWikiContextEntity value) { var d=value.getWikiDocument(); return new WikiContextView(d.getId(),d.getTitle(),d.getDocumentType(),d.getCurrentRevision()==null?0:d.getCurrentRevision().getRevisionNo()); }
    public record WikiContextView(Long documentId, String title, String documentType, int latestRevisionNo) {}
    public record WikiContextList(List<WikiContextView> items, int limit) {}
    public record WikiReadView(Long documentId, String title, String documentType, int revisionNo, String content, int offset, boolean hasMore) {}
    private List<Map<String,Object>> skillSnapshots(DocumentAgentSessionEntity session){if(session.getStageId()==null)return List.of();return workflow.agentContext(session.getStageId(),session.getOwner().getId()).skills().stream().map(s->{Map<String,Object> m=new LinkedHashMap<>();m.put("skill_key",s.skillKey());m.put("version_id",s.versionId());m.put("version",s.version());m.put("sha256",s.sha256());return m;}).toList();}
    public record SessionView(String sessionKey, String projectKey, Long ownerId, String profileKey, Long documentId, String targetTitle, String status, String runtimeSessionId, String conversationId, String workspaceId, Instant lastActivityAt, Instant closedAt, Long stageId) {}
    public record JobView(String jobKey, String sessionKey, int sequenceNo, String status, String runtimeJobId, String errorCode, String errorMessage, boolean retryable, Instant startedAt, Instant finishedAt, Long artifactId, Long revisionId, String documentUrl, String turnMode, Long targetDocumentId, String targetTitle) {}
    public record MessageView(String jobKey,int sequenceNo,String turnMode,String instruction,Long requestedBy,String requestedByName,String assistantContent,String status,String errorCode,String errorMessage,Long artifactId,Long revisionId,Instant createdAt,Instant finishedAt) {}
}
