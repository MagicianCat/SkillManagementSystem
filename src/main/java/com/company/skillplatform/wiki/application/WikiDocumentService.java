package com.company.skillplatform.wiki.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillOwnerRepository;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.*;
import com.company.skillplatform.wiki.infrastructure.entity.*;
import com.company.skillplatform.wiki.infrastructure.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiDocumentService {
    private static final int MAX_CONTENT_CHARS = 1_000_000;
    private final WikiDocumentRepository documents;
    private final WikiDocumentRevisionRepository revisions;
    private final WikiDocumentSkillRepository links;
    private final SkillRepository skills;
    private final SkillOwnerRepository owners;
    private final OrgTeamRepository teams;
    private final OrgTeamMemberRepository members;
    private final ScopedRoleAssignmentRepository scopedRoles;
    private final IamUserRepository users;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private WikiDocumentReviewRepository reviewRepository;

    public WikiDocumentService(WikiDocumentRepository documents, WikiDocumentRevisionRepository revisions,
            WikiDocumentSkillRepository links, SkillRepository skills, SkillOwnerRepository owners,
            OrgTeamRepository teams, OrgTeamMemberRepository members, ScopedRoleAssignmentRepository scopedRoles,
            IamUserRepository users, AuditService audit, ApplicationEventPublisher events) {
        this.documents = documents; this.revisions = revisions; this.links = links; this.skills = skills;
        this.owners = owners; this.teams = teams; this.members = members; this.scopedRoles = scopedRoles;
        this.users = users; this.audit = audit; this.events = events;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setReviewRepository(WikiDocumentReviewRepository reviewRepository) { this.reviewRepository = reviewRepository; }

    @Transactional(readOnly = true)
    public List<TeamView> visibleTeams(Long userId) {
        if (isAdmin()) return teams.findByStatusOrderByTeamNameAsc("ACTIVE").stream().map(this::teamView).toList();
        return members.findByUserId(userId).stream().filter(m -> "ACTIVE".equals(m.getStatus()))
                .map(m -> m.getTeam()).filter(t -> "ACTIVE".equals(t.getStatus())).distinct().map(this::teamView).toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<TeamView> searchTeams(Long userId, String keyword, Pageable pageable) {
        String term = keyword == null ? "" : keyword.trim();
        int size = Math.min(Math.max(pageable.getPageSize(), 1), 50);
        Page<TeamView> page;
        if (isAdmin()) {
            var rows = teams.findByStatusAndTeamNameContainingIgnoreCaseOrderByTeamNameAsc("ACTIVE", term, PageRequest.of(0, size))
                    .stream().map(this::teamView).toList();
            page = new PageImpl<>(rows, PageRequest.of(0, size), rows.size());
        } else {
            var rows = members.findByUserId(userId).stream().filter(m -> "ACTIVE".equals(m.getStatus()))
                    .map(m -> m.getTeam()).filter(t -> "ACTIVE".equals(t.getStatus()))
                    .filter(t -> term.isBlank() || t.getTeamName().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)))
                    .distinct().sorted(Comparator.comparing(t -> t.getTeamName(), String.CASE_INSENSITIVE_ORDER)).map(this::teamView).toList();
            int from = Math.min((int) pageable.getOffset(), rows.size());
            int to = Math.min(from + size, rows.size());
            page = new PageImpl<>(rows.subList(from, to), PageRequest.of(pageable.getPageNumber(), size), rows.size());
        }
        return PageResponse.from(page, v -> v);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentView> search(Long userId, Long teamId, String skillKey, String type, String keyword, Pageable pageable) {
        Specification<WikiDocumentEntity> spec = visibility(userId);
        if (teamId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("teamId"), teamId));
        if (type != null && !type.isBlank()) spec = spec.and((root, q, cb) -> cb.equal(root.get("documentType"), type.toUpperCase(Locale.ROOT)));
        if (keyword != null && !keyword.isBlank()) spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("title")), "%" + keyword.toLowerCase(Locale.ROOT) + "%"));
        if (skillKey != null && !skillKey.isBlank()) {
            spec = spec.and((root, q, cb) -> {
                var sub = q.subquery(Long.class); var link = sub.from(WikiDocumentSkillEntity.class);
                sub.select(link.get("document").get("id")).where(cb.equal(link.get("skill").get("skillKey"), skillKey));
                return root.get("id").in(sub);
            });
        }
        return PageResponse.from(documents.findAll(spec, limited(pageable)), this::view);
    }

    @Transactional(readOnly = true)
    public DocumentView get(Long id, Long userId) { return view(visibleDocument(id, userId)); }

    /** Validates that the actor may explicitly share this Wiki with a project stage. */
    @Transactional(readOnly = true)
    public WikiDocumentEntity selectableForDocumentAgent(Long id, Long userId) {
        WikiDocumentEntity document = visibleDocument(id, userId);
        if (document.getCurrentRevision() == null) throw error("WIKI_REVISION_REQUIRED", "Selected Wiki document has no revision", HttpStatus.BAD_REQUEST);
        return document;
    }

    /** Reads the latest revision after the document-agent job whitelist has been checked by its control plane. */
    @Transactional(readOnly = true)
    public AgentDocumentView readProjectSharedForDocumentAgent(Long id) {
        WikiDocumentEntity document = documents.findById(id).filter(d -> "ACTIVE".equals(d.getStatus()))
                .orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND));
        WikiDocumentRevisionEntity revision = document.getCurrentRevision();
        if (revision == null) throw error("WIKI_REVISION_REQUIRED", "Wiki document has no revision", HttpStatus.NOT_FOUND);
        return new AgentDocumentView(document.getId(), document.getTitle(), document.getDocumentType(), revision.getRevisionNo(), revision.getMarkdownContent());
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(Long id, Long userId) {
        WikiDocumentEntity document = visibleDocument(id, userId);
        return revisions.findByDocumentIdOrderByRevisionNoDesc(id, PageRequest.of(0, 100)).stream()
                .map(r -> new RevisionView(r.getId(), r.getRevisionNo(), r.getMarkdownContent(), r.getCreatedBy().getId(), r.getCreatedBy().getDisplayName(), r.getTimeCreated())).toList();
    }

    @Transactional
    public DocumentView create(CreateCommand command, Long userId, String requestId) {
        IamUserEntity actor = user(userId); String type = normalizeType(command.documentType());
        String title = title(command.title()); String content = content(command.markdownContent());
        List<SkillEntity> selected = selectedSkills(command.skillIds());
        Long teamId = command.teamId();
        if ("SKILL_GUIDE".equals(type)) {
            if (teamId == null) {
                if (!isAdmin()) throw error("PLATFORM_ADMIN_REQUIRED", "Platform administrator permission required", HttpStatus.FORBIDDEN);
                if (selected.stream().anyMatch(s -> "TEAM".equals(s.getScopeType()))) throw error("WIKI_PRIVATE_SKILL_LINK", "A platform Wiki cannot link a private Skill", HttpStatus.BAD_REQUEST);
            } else {
                if (!isTeamAdmin(teamId, userId)) throw error("TEAM_ADMIN_REQUIRED", "Team administrator permission required", HttpStatus.FORBIDDEN);
                validateTeamLinks(selected, teamId);
            }
        } else {
            if (selected.size() != 1) throw error("WIKI_README_SINGLE_SKILL_REQUIRED", "A README must be linked to exactly one Skill", HttpStatus.BAD_REQUEST);
            SkillEntity skill = selected.get(0);
            if (!owners.existsBySkillIdAndUserId(skill.getId(), userId) && !isAdmin()) throw error("SKILL_OWNER_REQUIRED", "Only a Skill owner may maintain its README", HttpStatus.FORBIDDEN);
            teamId = "TEAM".equals(skill.getScopeType()) ? skill.getTeamId() : null;
        }
        boolean platform = teamId == null;
        if ("SKILL_README".equals(type) && documents.findAll((root, q, cb) -> cb.and(
                cb.equal(root.get("documentType"), type), cb.equal(root.get("status"), "ACTIVE"),
                cb.equal(root.get("platformVisible"), platform))).stream().anyMatch(d -> links.findByDocumentIdOrderBySortOrderAsc(d.getId()).stream().anyMatch(l -> l.getSkill().getId().equals(selected.get(0).getId()))))
            throw error("README_ALREADY_EXISTS", "This Skill already has an active README", HttpStatus.CONFLICT);
        WikiDocumentEntity document = documents.save(new WikiDocumentEntity(title, type, teamId, platform, actor));
        WikiDocumentRevisionEntity revision = revisions.save(new WikiDocumentRevisionEntity(document, 1, content, sha256(content), actor));
        document.setCurrentRevision(revision, actor); documents.save(document);
        saveLinks(document, selected, type);
        audit.success("WIKI_DOCUMENT_CREATED", actor, "WIKI_DOCUMENT", document.getId(), requestId, null, Map.of("documentType", type), Map.of());
        events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(document.getId(), com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));
        return view(document);
    }

    @Transactional
    public DocumentView update(Long id, UpdateCommand command, Long userId, String requestId) {
        WikiDocumentEntity document = editable(id, userId); IamUserEntity actor = user(userId);
        if (document.getVersionNo() != command.versionNo()) throw error("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", HttpStatus.CONFLICT);
        String value = content(command.markdownContent()); document.update(title(command.title()), actor);
        int next = document.getCurrentRevision() == null ? 1 : document.getCurrentRevision().getRevisionNo() + 1;
        WikiDocumentRevisionEntity revision = revisions.save(new WikiDocumentRevisionEntity(document, next, value, sha256(value), actor));
        document.setCurrentRevision(revision, actor); documents.saveAndFlush(document);
        audit.success("WIKI_DOCUMENT_UPDATED", actor, "WIKI_DOCUMENT", id, requestId, null, Map.of("revisionNo", next), Map.of());
        events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(id, com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));
        return view(document);
    }

    @Transactional
    public DocumentView restore(Long id, Long revisionId, int versionNo, Long userId, String requestId) {
        WikiDocumentEntity document = editable(id, userId); IamUserEntity actor = user(userId);
        if (document.getVersionNo() != versionNo) throw error("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", HttpStatus.CONFLICT);
        WikiDocumentRevisionEntity source = revisions.findById(revisionId).orElseThrow(() -> error("WIKI_REVISION_NOT_FOUND", "Wiki revision not found", HttpStatus.NOT_FOUND));
        if (!source.getDocument().getId().equals(id)) throw error("WIKI_REVISION_INVALID", "Revision does not belong to this document", HttpStatus.BAD_REQUEST);
        int next = document.getCurrentRevision().getRevisionNo() + 1;
        WikiDocumentRevisionEntity revision = revisions.save(new WikiDocumentRevisionEntity(document, next, source.getMarkdownContent(), sha256(source.getMarkdownContent()), actor));
        document.setCurrentRevision(revision, actor); documents.saveAndFlush(document);
        audit.success("WIKI_DOCUMENT_RESTORED", actor, "WIKI_DOCUMENT", id, requestId, null, Map.of("sourceRevisionId", revisionId, "revisionNo", next), Map.of());
        events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(id, com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));
        return view(document);
    }

    @Transactional
    public void archive(Long id, Long userId, String requestId) { WikiDocumentEntity d = editable(id, userId); IamUserEntity actor = user(userId); d.archive(actor); documents.save(d); audit.success("WIKI_DOCUMENT_ARCHIVED", actor, "WIKI_DOCUMENT", id, requestId, null, Map.of(), Map.of()); events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(id, com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.DELETE)); }

    @Transactional(readOnly = true)
    public List<PromotionDocument> validatePromotion(Long skillId, Long teamId, List<Long> requestedIds) {
        List<WikiDocumentEntity> linked = links.findBySkillId(skillId).stream().map(WikiDocumentSkillEntity::getDocument).filter(d -> "ACTIVE".equals(d.getStatus())).toList();
        List<WikiDocumentEntity> selected = new ArrayList<>(linked.stream().filter(d -> "SKILL_README".equals(d.getDocumentType())).toList());
        for (Long id : requestedIds == null ? List.<Long>of() : requestedIds) {
            WikiDocumentEntity document = documents.findById(id).orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND));
            if (!"SKILL_GUIDE".equals(document.getDocumentType()) || !Objects.equals(document.getTeamId(), teamId) || links.findByDocumentIdOrderBySortOrderAsc(id).stream().noneMatch(l -> l.getSkill().getId().equals(skillId))) throw error("WIKI_PROMOTION_DOCUMENT_INVALID", "Selected Wiki document cannot be promoted with this Skill", HttpStatus.BAD_REQUEST);
            if (document.getCurrentRevision() == null) throw error("WIKI_REVISION_REQUIRED", "Selected Wiki document has no revision", HttpStatus.BAD_REQUEST);
            if (links.findByDocumentIdOrderBySortOrderAsc(id).stream().anyMatch(l -> "TEAM".equals(l.getSkill().getScopeType()) && !Objects.equals(l.getSkill().getId(), skillId)))
                throw error("WIKI_PRIVATE_SKILL_LINK", "A promoted Wiki cannot expose another private Skill", HttpStatus.BAD_REQUEST);
            selected.add(document);
        }
        return selected.stream().collect(Collectors.toMap(WikiDocumentEntity::getId, d -> new PromotionDocument(d, d.getCurrentRevision()), (a,b) -> a, LinkedHashMap::new)).values().stream().toList();
    }
    @Transactional public void promoteDocuments(List<Long> documentIds, Long actorId) { IamUserEntity actor=user(actorId); for(Long id:documentIds) documents.findById(id).filter(d->"ACTIVE".equals(d.getStatus())).ifPresent(d->{d.publishToPlatform(actor);documents.save(d);events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(id, com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));}); }

    public boolean canEdit(WikiDocumentEntity document, Long userId) {
        if (isAdmin()) return true;
        if ("SKILL_GUIDE".equals(document.getDocumentType())) return document.getTeamId() != null && isTeamAdmin(document.getTeamId(), userId);
        return links.findByDocumentIdOrderBySortOrderAsc(document.getId()).stream().anyMatch(l -> owners.existsBySkillIdAndUserId(l.getSkill().getId(), userId));
    }

    private Specification<WikiDocumentEntity> visibility(Long userId) {
        if (isAdmin()) return (root, q, cb) -> cb.equal(root.get("status"), "ACTIVE");
        List<Long> teamIds = members.findByUserId(userId).stream().filter(m -> "ACTIVE".equals(m.getStatus())).map(m -> m.getTeam().getId()).toList();
        return (root, q, cb) -> {
            var active = cb.equal(root.get("status"), "ACTIVE");
            if (teamIds.isEmpty()) return cb.and(active, cb.isTrue(root.get("platformVisible")));
            return cb.and(active, cb.or(cb.isTrue(root.get("platformVisible")), root.get("teamId").in(teamIds)));
        };
    }
    private WikiDocumentEntity visibleDocument(Long id, Long userId) { return documents.findById(id).filter(d -> "ACTIVE".equals(d.getStatus())).filter(d -> isVisible(d, userId)).orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND)); }
    private boolean isVisible(WikiDocumentEntity d, Long userId) { return isAdmin() || d.isPlatformVisible() || (d.getTeamId() != null && members.findByUserId(userId).stream().anyMatch(m -> "ACTIVE".equals(m.getStatus()) && d.getTeamId().equals(m.getTeam().getId()))); }
    private WikiDocumentEntity editable(Long id, Long userId) { WikiDocumentEntity d = visibleDocument(id, userId); if (hasPendingReview(id)) throw error("WIKI_REVIEW_PENDING", "Wiki document has a pending platform review", HttpStatus.CONFLICT); if (!canEdit(d, userId)) throw error("WIKI_EDIT_FORBIDDEN", "You cannot edit this Wiki document", HttpStatus.FORBIDDEN); return d; }
    private List<SkillEntity> selectedSkills(List<Long> ids) { if (ids == null || ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) throw error("WIKI_SKILLS_REQUIRED", "At least one unique Skill is required", HttpStatus.BAD_REQUEST); List<SkillEntity> result = skills.findAllById(ids); if (result.size() != ids.size()) throw error("SKILL_NOT_FOUND", "A linked Skill was not found", HttpStatus.NOT_FOUND); return result; }
    private void validateTeamLinks(List<SkillEntity> selected, Long teamId) { if (selected.stream().anyMatch(s -> "TEAM".equals(s.getScopeType()) && !teamId.equals(s.getTeamId()))) throw error("WIKI_CROSS_TEAM_SKILL", "A team Wiki cannot link another team's private Skill", HttpStatus.BAD_REQUEST); }
    private void saveLinks(WikiDocumentEntity document, List<SkillEntity> selected, String type) { int i = 0; for (SkillEntity skill : selected) links.save(new WikiDocumentSkillEntity(document, skill, "SKILL_README".equals(type) ? "PRIMARY" : "RELATED", i++)); }
    private DocumentView view(WikiDocumentEntity d) { List<SkillLinkView> skillViews = links.findByDocumentIdOrderBySortOrderAsc(d.getId()).stream().map(l -> new SkillLinkView(l.getSkill().getId(), l.getSkill().getSkillKey(), l.getSkill().getDisplayName())).toList(); WikiDocumentRevisionEntity r = d.getCurrentRevision(); WikiDocumentReviewEntity pending = pendingReview(d.getId()); return new DocumentView(d.getId(), d.getTitle(), d.getDocumentType(), d.getTeamId(), d.isPlatformVisible(), r == null ? "" : r.getMarkdownContent(), r == null ? 0 : r.getRevisionNo(), d.getVersionNo(), skillViews, canEdit(d, currentUserId()) && pending == null, "ACTIVE".equals(d.getStatus()), pending == null ? null : pending.getId(), pending == null ? null : pending.getStatus().name()); }
    private TeamView teamView(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity t) { return new TeamView(t.getId(), t.getTeamName(), t.getParent() == null ? null : t.getParent().getId()); }
    private Pageable limited(Pageable p) { return PageRequest.of(p.getPageNumber(), Math.min(Math.max(p.getPageSize(), 1), 50), p.getSort().isSorted() ? p.getSort() : Sort.by("timeUpdated").descending()); }
    private String normalizeType(String value) { if (!Set.of("SKILL_README", "SKILL_GUIDE").contains(value == null ? "" : value.toUpperCase(Locale.ROOT))) throw error("WIKI_DOCUMENT_TYPE_INVALID", "Unsupported Wiki document type", HttpStatus.BAD_REQUEST); return value.toUpperCase(Locale.ROOT); }
    private String title(String value) { if (value == null || value.isBlank() || value.length() > 255) throw error("WIKI_TITLE_INVALID", "Title must contain 1..255 characters", HttpStatus.BAD_REQUEST); return value.trim(); }
    private String content(String value) { if (value == null || value.isBlank() || value.length() > MAX_CONTENT_CHARS) throw error("WIKI_MARKDOWN_INVALID", "Markdown must contain 1..1000000 characters", HttpStatus.BAD_REQUEST); return value; }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private Long currentUserId() { Authentication a = SecurityContextHolder.getContext().getAuthentication(); return a == null || !(a.getPrincipal() instanceof Long) ? null : (Long) a.getPrincipal(); }
    private boolean hasPendingReview(Long documentId) { return reviewRepository != null && reviewRepository.existsByDocumentIdAndStatus(documentId, com.company.skillplatform.wiki.domain.WikiReviewStatus.PENDING); }
    private WikiDocumentReviewEntity pendingReview(Long documentId) { return reviewRepository == null ? null : reviewRepository.findByDocumentIdAndStatus(documentId, com.company.skillplatform.wiki.domain.WikiReviewStatus.PENDING).orElse(null); }
    private boolean isAdmin() { Authentication a = SecurityContextHolder.getContext().getAuthentication(); return a != null && a.getAuthorities().stream().anyMatch(x -> "admin:identity".equals(x.getAuthority())); }
    private boolean isTeamAdmin(Long teamId, Long userId) { return scopedRoles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(userId, "TEAM_ADMIN", "TEAM", teamId).isPresent(); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> error("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND)); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record CreateCommand(String title, String documentType, Long teamId, List<Long> skillIds, String markdownContent) {}
    public record UpdateCommand(String title, String markdownContent, int versionNo) {}
    public record TeamView(Long id, String name, Long parentId) {}
    public record SkillLinkView(Long id, String skillKey, String displayName) {}
    public record DocumentView(Long id, String title, String documentType, Long teamId, boolean platformVisible, String markdownContent, int revisionNo, int versionNo, List<SkillLinkView> skills, boolean canEdit, boolean active, Long pendingPlatformReviewId, String pendingPlatformReviewStatus) {}
    public record RevisionView(Long id, int revisionNo, String markdownContent, Long createdBy, String createdByName, java.time.Instant createdAt) {}
    public record PromotionDocument(WikiDocumentEntity document, WikiDocumentRevisionEntity revision) {}
    public record AgentDocumentView(Long id, String title, String documentType, int revisionNo, String markdownContent) {}
}
