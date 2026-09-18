package com.company.skillplatform.project.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.project.infrastructure.entity.*;
import com.company.skillplatform.project.infrastructure.repository.*;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.user.infrastructure.repository.ScopedRoleAssignmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectControlService {
    private static final Set<String> DOCUMENT_TYPES = Set.of("REQUIREMENT", "PRD", "ARCHITECTURE", "UI_DESIGN");
    private static final Set<String> ROLES = Set.of("OWNER", "MAINTAINER", "MEMBER");
    private final VirtualProjectRepository projects;
    private final VirtualProjectMemberRepository members;
    private final ProjectDocumentRepository documents;
    private final ProjectDocumentRevisionRepository revisions;
    private final ProjectDocumentSourceRepository sources;
    private final IamUserRepository users;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final ScopedRoleAssignmentRepository scopedRoles;
    private final ProjectWorkflowService workflow;
    private final Clock clock = Clock.systemUTC();

    public ProjectControlService(VirtualProjectRepository projects, VirtualProjectMemberRepository members,
                                 ProjectDocumentRepository documents, ProjectDocumentRevisionRepository revisions, ProjectDocumentSourceRepository sources,
                                 IamUserRepository users, AuditService audit, ObjectMapper mapper,
                                 ScopedRoleAssignmentRepository scopedRoles, ProjectWorkflowService workflow) {
        this.projects = projects; this.members = members; this.documents = documents; this.revisions = revisions; this.sources = sources;
        this.users = users; this.audit = audit; this.mapper = mapper;
        this.scopedRoles = scopedRoles; this.workflow = workflow;
    }

    @Transactional
    public ProjectView create(CreateProject command, Long actorId, String requestId) {
        if (!isAdmin() && !scopedRoles.existsByUserIdAndRoleKeyAndScopeType(actorId, "PROJECT_MANAGER", "TEAM"))
            throw error("PROJECT_CREATE_FORBIDDEN", "Project manager role required", HttpStatus.FORBIDDEN);
        IamUserEntity actor = user(actorId);
        VirtualProjectEntity project = projects.save(new VirtualProjectEntity(validName(command.name()), trim(command.description(), 2000), actor));
        members.save(new VirtualProjectMemberEntity(project, actor, "OWNER", actor));
        workflow.initialize(project.getId(), command.enabledStages());
        audit.success("PROJECT_CREATED", actor, "VIRTUAL_PROJECT", project.getId(), requestId, Map.of(), Map.of("projectKey", project.getProjectKey()), Map.of());
        return projectView(project, actorId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectView> list(Long actorId, Pageable pageable) {
        List<VirtualProjectEntity> visible;
        if (isAdmin()) visible = projects.findByStatusOrderByTimeUpdatedDesc("ACTIVE", limited(pageable)).getContent();
        else visible = members.findByUserIdAndStatus(actorId, "ACTIVE").stream().map(VirtualProjectMemberEntity::getProject)
                .filter(p -> "ACTIVE".equals(p.getStatus())).sorted(Comparator.comparing(VirtualProjectEntity::getTimeUpdated).reversed()).toList();
        int size = Math.min(Math.max(pageable.getPageSize(), 1), 50);
        int from = Math.min((int) pageable.getOffset(), visible.size());
        int to = Math.min(from + size, visible.size());
        return new PageResponse<>(visible.subList(from, to).stream().map(p -> projectView(p, actorId)).toList(), pageable.getPageNumber(), size, visible.size(), (visible.size() + size - 1) / size);
    }

    @Transactional(readOnly = true)
    public ProjectView get(String key, Long actorId) { return projectView(access(key, actorId), actorId); }

    @Transactional(readOnly = true)
    public void assertAgentAccess(String key, Long actorId) { access(key, actorId); }

    @Transactional(readOnly = true)
    public Long projectIdForAgent(String key, Long actorId) { return access(key, actorId).getId(); }

    @Transactional
    public ProjectView update(String key, UpdateProject command, Long actorId, String requestId) {
        VirtualProjectEntity project = access(key, actorId); requireRole(project, actorId, "OWNER");
        if (project.getVersionNo() != command.versionNo()) throw conflict();
        IamUserEntity actor = user(actorId); project.update(validName(command.name()), trim(command.description(), 2000)); projects.saveAndFlush(project);
        audit.success("PROJECT_UPDATED", actor, "VIRTUAL_PROJECT", project.getId(), requestId, Map.of(), Map.of(), Map.of());
        return projectView(project, actorId);
    }

    @Transactional
    public void archive(String key, Long actorId, String requestId) {
        VirtualProjectEntity project = access(key, actorId); requireRole(project, actorId, "OWNER");
        IamUserEntity actor = user(actorId); project.archive(); projects.save(project);
        audit.success("PROJECT_ARCHIVED", actor, "VIRTUAL_PROJECT", project.getId(), requestId, Map.of(), Map.of(), Map.of());
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(String key, Long actorId) { return members.findByProjectIdAndStatusOrderByTimeCreatedAsc(access(key, actorId).getId(), "ACTIVE").stream().map(this::memberView).toList(); }

    @Transactional
    public MemberView upsertMember(String key, Long targetUserId, String role, Long actorId, String requestId) {
        VirtualProjectEntity project = access(key, actorId); requireRole(project, actorId, "OWNER");
        if (!ROLES.contains(role)) throw error("PROJECT_ROLE_INVALID", "Unsupported project role", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId); IamUserEntity target = user(targetUserId);
        VirtualProjectMemberEntity existing = members.findByProjectIdAndUserId(project.getId(), targetUserId).orElse(null);
        if (existing != null && "OWNER".equals(existing.getRoleKey()) && !"OWNER".equals(role)
                && members.countByProjectIdAndRoleKeyAndStatus(project.getId(), "OWNER", "ACTIVE") <= 1)
            throw error("PROJECT_OWNER_REQUIRED", "Project must keep one owner", HttpStatus.CONFLICT);
        if ("OWNER".equals(role)) {
            members.findByProjectIdAndStatusOrderByTimeCreatedAsc(project.getId(), "ACTIVE").stream()
                    .filter(m -> "OWNER".equals(m.getRoleKey()) && (existing == null || !m.getUser().getId().equals(targetUserId)))
                    .forEach(m -> { m.changeRole("MAINTAINER"); members.save(m); });
        }
        VirtualProjectMemberEntity row = existing;
        if (row == null) row = members.save(new VirtualProjectMemberEntity(project, target, role, actor)); else { row.changeRole(role); row = members.save(row); }
        audit.success("PROJECT_MEMBER_UPSERTED", actor, "VIRTUAL_PROJECT", project.getId(), requestId, Map.of(), Map.of("userId", targetUserId, "role", role), Map.of());
        return memberView(row);
    }

    @Transactional
    public MemberView transferOwnership(String key, Long targetUserId, Long actorId, String requestId) {
        return upsertMember(key, targetUserId, "OWNER", actorId, requestId);
    }

    @Transactional
    public void removeMember(String key, Long targetUserId, Long actorId, String requestId) {
        VirtualProjectEntity project = access(key, actorId); requireRole(project, actorId, "OWNER");
        VirtualProjectMemberEntity row = members.findByProjectIdAndUserId(project.getId(), targetUserId).orElseThrow(() -> error("PROJECT_MEMBER_NOT_FOUND", "Project member not found", HttpStatus.NOT_FOUND));
        if ("OWNER".equals(row.getRoleKey()) || members.countByProjectIdAndRoleKeyAndStatus(project.getId(), "OWNER", "ACTIVE") <= 1) throw error("PROJECT_OWNER_REQUIRED", "Project must keep one owner", HttpStatus.CONFLICT);
        row.deactivate(); members.save(row); audit.success("PROJECT_MEMBER_REMOVED", user(actorId), "VIRTUAL_PROJECT", project.getId(), requestId, Map.of(), Map.of("userId", targetUserId), Map.of());
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentView> listDocuments(String key, Long actorId, Pageable pageable) {
        VirtualProjectEntity project = access(key, actorId);
        return PageResponse.from(documents.findByProjectIdAndStatusNotOrderByTimeUpdatedDesc(project.getId(), "ARCHIVED", limited(pageable)), d -> documentView(d));
    }

    @Transactional
    public DocumentView createDocument(String key, CreateDocument command, Long actorId, String requestId) {
        VirtualProjectEntity project = access(key, actorId); IamUserEntity actor = user(actorId); validateType(command.documentType());
        ProjectDocumentEntity document = documents.save(new ProjectDocumentEntity(project, command.documentType(), validTitle(command.title()), actor, clock.instant()));
        saveRevision(document, command.markdownContent(), "HUMAN", null, null, null, command.skillSnapshots(), command.assumptions(), command.openQuestions(), command.sourceDocumentIds(), actor);
        audit.success("PROJECT_DOCUMENT_CREATED", actor, "PROJECT_DOCUMENT", document.getId(), requestId, Map.of(), Map.of("projectKey", key, "documentType", command.documentType()), Map.of());
        return documentView(document);
    }

    /** Creates the first revision for an OpenHands job and records its provenance. */
    @Transactional
    public DocumentView createAgentDocument(String key, AgentDocument command, Long actorId, String requestId) {
        VirtualProjectEntity project = access(key, actorId);
        IamUserEntity actor = user(actorId);
        validateType(command.documentType());
        ProjectDocumentEntity document = documents.save(new ProjectDocumentEntity(project, command.documentType(), validTitle(command.title()), actor, clock.instant()));
        saveRevision(document, command.markdownContent(), "AGENT", command.profileKey(), command.agentSessionId(), command.agentJobId(), command.skillSnapshots(), command.assumptions(), command.openQuestions(), command.sourceDocumentIds(), actor);
        audit.success("PROJECT_DOCUMENT_AGENT_CREATED", actor, "PROJECT_DOCUMENT", document.getId(), requestId, Map.of(), Map.of("projectKey", key, "agentJobId", command.agentJobId()), Map.of());
        return documentView(document);
    }

    @Transactional(readOnly = true)
    public DocumentView getDocument(String key, Long documentId, Long actorId) { return documentView(document(key, documentId, actorId)); }

    @Transactional
    public DocumentView saveDraft(String key, Long documentId, SaveDraft command, Long actorId, String requestId) {
        ProjectDocumentEntity document = document(key, documentId, actorId); requireMember(document.getProject(), actorId);
        if (document.getVersionNo() != command.versionNo()) throw conflict();
        IamUserEntity actor = user(actorId);
        ProjectDocumentRevisionEntity revision = saveRevision(document, command.markdownContent(), command.sourceType(), command.profileKey(), command.agentSessionId(), command.agentJobId(), command.skillSnapshots(), command.assumptions(), command.openQuestions(), command.sourceDocumentIds(), actor);
        if (command.title() != null && !command.title().isBlank()) document.updateTitle(validTitle(command.title()), actor);
        documents.saveAndFlush(document);
        audit.success("PROJECT_DOCUMENT_DRAFT_SAVED", actor, "PROJECT_DOCUMENT", document.getId(), requestId, Map.of(), Map.of("revisionNo", revision.getRevisionNo(), "sourceType", command.sourceType()), Map.of());
        return documentView(document);
    }

    @Transactional
    public DocumentView publish(String key, Long documentId, PublishCommand command, Long actorId, String requestId) {
        ProjectDocumentEntity document = document(key, documentId, actorId); requireRole(document.getProject(), actorId, "MAINTAINER");
        if (document.getVersionNo() != command.versionNo()) throw conflict();
        ProjectDocumentRevisionEntity revision = revisions.findByIdAndDocumentId(command.revisionId(), documentId).orElseThrow(() -> error("PROJECT_REVISION_NOT_FOUND", "Project document revision not found", HttpStatus.NOT_FOUND));
        IamUserEntity actor = user(actorId); document.publish(revision, actor); documents.saveAndFlush(document);
        audit.success("PROJECT_DOCUMENT_PUBLISHED", actor, "PROJECT_DOCUMENT", document.getId(), requestId, Map.of(), Map.of("revisionNo", revision.getRevisionNo()), Map.of());
        return documentView(document);
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(String key, Long documentId, Long actorId) {
        document(key, documentId, actorId);
        return revisions.findByDocumentIdOrderByRevisionNoDesc(documentId, PageRequest.of(0, 200)).stream().map(this::revisionView).toList();
    }

    @Scheduled(cron = "${skill-platform.project.draft-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void cleanupExpiredDrafts() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(180));
        for (ProjectDocumentEntity document : documents.findByEverPublishedFalseAndStatusAndLastDraftActivityAtBefore("DRAFT", cutoff)) {
            document.expireDraft(document.getCreatedBy()); documents.saveAndFlush(document); revisions.deleteByDocumentId(document.getId());
        }
    }

    private ProjectDocumentRevisionEntity saveRevision(ProjectDocumentEntity document, String content, String sourceType, String profileKey, String sessionId, String jobId, Object snapshots, Object assumptions, Object questions, List<Long> sourceDocumentIds, IamUserEntity actor) {
        String value = validContent(content); int next = (int) revisions.countByDocumentId(document.getId()) + 1;
        ProjectDocumentRevisionEntity revision = revisions.save(new ProjectDocumentRevisionEntity(document, next, value, sha256(value), sourceType == null ? "HUMAN" : sourceType, profileKey, sessionId, jobId, json(snapshots), json(assumptions), json(questions), actor));
        document.draft(revision, actor, clock.instant()); documents.save(document); saveSources(document, sourceDocumentIds); return revision;
    }
    private void saveSources(ProjectDocumentEntity document, List<Long> sourceIds) {
        if (sourceIds == null) return;
        sources.deleteByDocumentId(document.getId());
        for (Long sourceId : new LinkedHashSet<>(sourceIds)) {
            ProjectDocumentEntity source = documents.findByIdAndProjectId(sourceId, document.getProject().getId()).orElseThrow(() -> error("PROJECT_SOURCE_NOT_FOUND", "Source document not found", HttpStatus.BAD_REQUEST));
            if (source.getId().equals(document.getId())) throw error("PROJECT_SOURCE_CYCLE", "A document cannot reference itself", HttpStatus.BAD_REQUEST);
            sources.save(new ProjectDocumentSourceEntity(document, source, source.getPublishedRevision() == null ? null : source.getPublishedRevision().getId()));
        }
    }
    private VirtualProjectEntity access(String key, Long actorId) { VirtualProjectEntity project = projects.findByProjectKey(key).filter(p -> "ACTIVE".equals(p.getStatus())).orElseThrow(() -> error("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND)); if (!isAdmin() && !members.existsByProjectIdAndUserIdAndStatus(project.getId(), actorId, "ACTIVE")) throw error("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND); return project; }
    private ProjectDocumentEntity document(String key, Long documentId, Long actorId) { VirtualProjectEntity project = access(key, actorId); return documents.findByIdAndProjectId(documentId, project.getId()).filter(d -> !"ARCHIVED".equals(d.getStatus())).orElseThrow(() -> error("PROJECT_DOCUMENT_NOT_FOUND", "Project document not found", HttpStatus.NOT_FOUND)); }
    private void requireMember(VirtualProjectEntity project, Long actorId) { if (!isAdmin() && !members.existsByProjectIdAndUserIdAndStatus(project.getId(), actorId, "ACTIVE")) throw error("PROJECT_ACCESS_FORBIDDEN", "Project membership required", HttpStatus.FORBIDDEN); }
    private void requireRole(VirtualProjectEntity project, Long actorId, String minimum) { if (isAdmin()) return; VirtualProjectMemberEntity row = members.findByProjectIdAndUserId(project.getId(), actorId).filter(m -> "ACTIVE".equals(m.getStatus())).orElseThrow(() -> error("PROJECT_ACCESS_FORBIDDEN", "Project membership required", HttpStatus.FORBIDDEN)); boolean allowed = "OWNER".equals(row.getRoleKey()) || ("MAINTAINER".equals(minimum) && "MAINTAINER".equals(row.getRoleKey())); if (!allowed) throw error("PROJECT_ACCESS_FORBIDDEN", "Project role is insufficient", HttpStatus.FORBIDDEN); }
    private ProjectView projectView(VirtualProjectEntity p, Long actorId) { String role = members.findByProjectIdAndUserId(p.getId(), actorId).map(VirtualProjectMemberEntity::getRoleKey).orElse(isAdmin() ? "ADMIN" : null); return new ProjectView(p.getProjectKey(), p.getProjectName(), p.getDescription(), p.getStatus(), role, p.getVersionNo()); }
    private MemberView memberView(VirtualProjectMemberEntity m) { return new MemberView(m.getUser().getId(), m.getUser().getDisplayName(), m.getRoleKey(), m.getStatus()); }
    private DocumentView documentView(ProjectDocumentEntity d) { ProjectDocumentRevisionEntity draft = d.getCurrentDraftRevision(), published = d.getPublishedRevision(); return new DocumentView(d.getId(), d.getProject().getProjectKey(), d.getDocumentType(), d.getTitle(), d.getStatus(), draft == null ? null : revisionView(draft), published == null ? null : revisionView(published), d.isEverPublished(), d.getLastDraftActivityAt(), d.getVersionNo()); }
    private RevisionView revisionView(ProjectDocumentRevisionEntity r) { return new RevisionView(r.getId(), r.getRevisionNo(), r.getMarkdownContent(), r.getContentSha256(), r.getSourceType(), r.getProfileKey(), r.getAgentSessionId(), r.getAgentJobId(), r.getCreatedBy().getId(), r.getCreatedBy().getDisplayName(), r.getTimeCreated()); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> error("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND)); }
    private boolean isAdmin() { Authentication a = SecurityContextHolder.getContext().getAuthentication(); return a != null && a.getAuthorities().stream().anyMatch(x -> "admin:identity".equals(x.getAuthority())); }
    private String validName(String value) { if (value == null || value.isBlank() || value.trim().length() > 255) throw error("PROJECT_NAME_INVALID", "Project name must contain 1..255 characters", HttpStatus.BAD_REQUEST); return value.trim(); }
    private String validTitle(String value) { if (value == null || value.isBlank() || value.trim().length() > 255) throw error("PROJECT_DOCUMENT_TITLE_INVALID", "Document title must contain 1..255 characters", HttpStatus.BAD_REQUEST); return value.trim(); }
    private String validContent(String value) { if (value == null || value.isBlank() || value.length() > 1_000_000) throw error("PROJECT_DOCUMENT_CONTENT_INVALID", "Markdown must contain 1..1000000 characters", HttpStatus.BAD_REQUEST); return value; }
    private void validateType(String type) { if (type == null || !DOCUMENT_TYPES.contains(type.toUpperCase(Locale.ROOT))) throw error("PROJECT_DOCUMENT_TYPE_INVALID", "Unsupported project document type", HttpStatus.BAD_REQUEST); }
    private String trim(String value, int max) { return value == null ? null : value.trim().substring(0, Math.min(value.trim().length(), max)); }
    private String json(Object value) { if (value == null) return null; try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw error("PROJECT_DOCUMENT_METADATA_INVALID", "Document metadata is invalid", HttpStatus.BAD_REQUEST); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private Pageable limited(Pageable p) { return PageRequest.of(p.getPageNumber(), Math.min(Math.max(p.getPageSize(), 1), 50), p.getSort().isSorted() ? p.getSort() : Sort.by("timeUpdated").descending()); }
    private BusinessException conflict() { return error("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", HttpStatus.CONFLICT); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record CreateProject(String name, String description, List<String> enabledStages) {
        public CreateProject(String name, String description) { this(name, description, null); }
    }
    public record UpdateProject(String name, String description, int versionNo) {}
    public record CreateDocument(String documentType, String title, String markdownContent, Object skillSnapshots, Object assumptions, Object openQuestions, List<Long> sourceDocumentIds) {}
    public record AgentDocument(String documentType, String title, String markdownContent, String profileKey, String agentSessionId, String agentJobId, Object skillSnapshots, Object assumptions, Object openQuestions, List<Long> sourceDocumentIds) {}
    public record SaveDraft(String title, String markdownContent, int versionNo, String sourceType, String profileKey, String agentSessionId, String agentJobId, Object skillSnapshots, Object assumptions, Object openQuestions, List<Long> sourceDocumentIds) {}
    public record PublishCommand(Long revisionId, int versionNo) {}
    public record ProjectView(String projectKey, String name, String description, String status, String role, int versionNo) {}
    public record MemberView(Long userId, String displayName, String role, String status) {}
    public record DocumentView(Long id, String projectKey, String documentType, String title, String status, RevisionView draft, RevisionView published, boolean everPublished, Instant lastDraftActivityAt, int versionNo) {}
    public record RevisionView(Long id, int revisionNo, String markdownContent, String contentSha256, String sourceType, String profileKey, String agentSessionId, String agentJobId, Long createdBy, String createdByName, Instant createdAt) {}
}
