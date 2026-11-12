package com.company.skillplatform.wiki.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.domain.SkillStatus;
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
    private WikiPromotionStore promotionStore;

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
    @org.springframework.beans.factory.annotation.Autowired
    public void setPromotionStore(WikiPromotionStore promotionStore) { this.promotionStore = promotionStore; }

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
    public PageResponse<DocumentView> search(Long userId, Long teamId, String skillKey, String keyword, Pageable pageable) {
        Specification<WikiDocumentEntity> spec = visibility(userId);
        Set<Long> visibleTeamIds = members.findByUserId(userId).stream().filter(m -> "ACTIVE".equals(m.getStatus()) && "ACTIVE".equals(m.getTeam().getStatus())).map(m -> m.getTeam().getId()).collect(Collectors.toSet());
        if (teamId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("teamId"), teamId));
        if (keyword != null && !keyword.isBlank()) spec = spec.and((root, q, cb) -> {
            var approved = q.subquery(Long.class); var snapshot = approved.from(WikiPlatformSnapshotEntity.class);
            approved.select(snapshot.get("documentId")).where(cb.like(cb.lower(snapshot.get("approvedTitle")), "%" + keyword.toLowerCase(Locale.ROOT) + "%"));
            var teamTitle = cb.like(cb.lower(root.get("title")), "%" + keyword.toLowerCase(Locale.ROOT) + "%");
            return isAdmin() ? teamTitle : visibleTeamIds.isEmpty() ? root.get("id").in(approved)
                    : cb.or(cb.and(root.get("teamId").in(visibleTeamIds), teamTitle), root.get("id").in(approved));
        });
        if (skillKey != null && !skillKey.isBlank()) {
            List<Long> publicIds = promotionStore == null ? List.of() : promotionStore.publicDocumentIdsForSkillKey(skillKey);
            spec = spec.and((root, q, cb) -> {
                var sub = q.subquery(Long.class); var link = sub.from(WikiDocumentSkillEntity.class);
                sub.select(link.get("document").get("id")).where(cb.equal(link.get("skill").get("skillKey"), skillKey));
                return isAdmin() ? root.get("id").in(sub) : cb.or(cb.and(root.get("teamId").in(visibleTeamIds), root.get("id").in(sub)), root.get("id").in(publicIds));
            });
        }
        return PageResponse.from(documents.findAll(spec, limited(pageable)), this::view);
    }

    @Transactional(readOnly = true)
    public List<DocumentMatchView> findTeamDocumentsByTitle(Long userId, Long teamId, String requestedTitle) {
        if (teamId == null || teamId <= 0) throw error("WIKI_TEAM_REQUIRED", "A team is required", HttpStatus.BAD_REQUEST);
        if (!isAdmin() && !isActiveMember(teamId, userId)) throw error("WIKI_TEAM_MEMBER_REQUIRED", "Active team membership required", HttpStatus.FORBIDDEN);
        String exactTitle = title(requestedTitle);
        Specification<WikiDocumentEntity> spec = visibility(userId).and((root, q, cb) -> cb.and(
                cb.equal(root.get("teamId"), teamId),
                cb.equal(cb.lower(root.get("title")), exactTitle.toLowerCase(Locale.ROOT))));
        return documents.findAll(spec, Sort.by("id").ascending()).stream()
                .map(document -> new DocumentMatchView(document.getId(), document.getTitle(),
                        document.getCurrentRevision() == null ? 0 : document.getCurrentRevision().getRevisionNo(),
                        canEdit(document, userId) && !hasPendingReview(document.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentView> children(Long parentId, Long userId) {
        WikiDocumentEntity parent = visibleDocument(parentId, userId);
        List<WikiDocumentEntity> children = promotionStore == null || isAdmin() || isActiveMember(parent.getTeamId(), userId)
                ? documents.findByParentIdAndStatusOrderByIdAsc(parentId, "ACTIVE")
                : promotionStore.platformChildren(parentId).stream().map(documents::findById).flatMap(Optional::stream).toList();
        return children.stream()
                .filter(document -> isVisible(document, userId)).map(this::view).toList();
    }

    /** Returns a bounded directory of visible descendants without loading their Markdown bodies. */
    @Transactional(readOnly = true)
    public DescendantDirectory visibleDescendants(Long parentId, Long userId, boolean platformOnly, int requestedDepth, int requestedLimit) {
        WikiDocumentEntity parent = visibleDocument(parentId, userId);
        if (platformOnly && !parent.isPlatformVisible()) throw error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND);
        int maxDepth = Math.min(Math.max(requestedDepth, 1), 3);
        int maxItems = Math.min(Math.max(requestedLimit, 1), 20);
        boolean admin = !platformOnly && isAdmin();
        Set<Long> teamIds = platformOnly || admin ? Set.of() : members.findByUserId(userId).stream()
                .filter(member -> "ACTIVE".equals(member.getStatus())).map(member -> member.getTeam().getId()).collect(Collectors.toSet());
        List<DescendantSummary> found = new ArrayList<>();
        Deque<DirectoryNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        visited.add(parentId);
        queue.addLast(new DirectoryNode(parentId, 0));
        while (!queue.isEmpty()) {
            DirectoryNode node = queue.removeFirst();
            List<WikiDocumentEntity> children = promotionStore != null && (platformOnly || (!admin && !isActiveMember(parent.getTeamId(), userId)))
                    ? promotionStore.platformChildren(node.id()).stream().map(documents::findById).flatMap(Optional::stream).toList()
                    : documents.findByParentIdAndStatusOrderByIdAsc(node.id(), "ACTIVE");
            for (WikiDocumentEntity child : children) {
                if (!child.isPlatformVisible() && (platformOnly || (!admin && !teamIds.contains(child.getTeamId())))) continue;
                if (!visited.add(child.getId())) continue;
                if (node.depth() >= maxDepth || found.size() >= maxItems) return new DescendantDirectory(List.copyOf(found), true);
                int depth = node.depth() + 1;
                WikiPromotionStore.PlatformSnapshot approved = promotionStore != null && (platformOnly || (!admin && !isActiveMember(parent.getTeamId(), userId))) ? promotionStore.platform(child.getId()) : null;
                found.add(new DescendantSummary(child.getId(), approved == null ? child.getTitle() : approved.title(), node.id(), depth));
                queue.addLast(new DirectoryNode(child.getId(), depth));
            }
        }
        return new DescendantDirectory(List.copyOf(found), false);
    }

    private record DirectoryNode(Long id, int depth) {}
    public record DescendantSummary(Long documentId, String title, Long parentId, int depth) {}
    public record DescendantDirectory(List<DescendantSummary> items, boolean truncated) {}

    @Transactional
    public void markExternalSource(Long documentId, String sourceSystem, String resourceId, String entryPath) {
        WikiDocumentEntity document = documents.findByIdAndStatus(documentId, "ACTIVE")
                .orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND));
        document.setExternalSource(sourceSystem, resourceId, entryPath);
        documents.save(document);
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
        if (document.isPlatformVisible() && promotionStore != null) {
            WikiPromotionStore.PlatformSnapshot approved = promotionStore.platform(id);
            if (approved != null) return new AgentDocumentView(id, approved.title(), approved.revisionNo(), approved.markdownContent());
        }
        return new AgentDocumentView(document.getId(), document.getTitle(), revision.getRevisionNo(), revision.getMarkdownContent());
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(Long id, Long userId) {
        WikiDocumentEntity document = visibleDocument(id, userId);
        if (!isAdmin() && !isActiveMember(document.getTeamId(), userId)) {
            WikiPromotionStore.PlatformSnapshot approved = promotionStore.platform(id);
            return revisions.findById(approved.revisionId()).stream().map(r -> new RevisionView(r.getId(), r.getRevisionNo(), r.getMarkdownContent(), r.getCreatedBy().getId(), r.getCreatedBy().getDisplayName(), r.getTimeCreated())).toList();
        }
        return revisions.findByDocumentIdOrderByRevisionNoDesc(id, PageRequest.of(0, 100)).stream()
                .map(r -> new RevisionView(r.getId(), r.getRevisionNo(), r.getMarkdownContent(), r.getCreatedBy().getId(), r.getCreatedBy().getDisplayName(), r.getTimeCreated())).toList();
    }

    @Transactional
    public DocumentView create(CreateCommand command, Long userId, String requestId) {
        requireAuthority("wiki:create");
        IamUserEntity actor = user(userId);
        String title = title(command.title()); String content = content(command.markdownContent());
        Long teamId = command.teamId();
        if (teamId == null ? !isAdmin() : !isActiveMember(teamId, userId) && !isAdmin())
            throw error("WIKI_TEAM_MEMBER_REQUIRED", "Active team membership required", HttpStatus.FORBIDDEN);
        List<SkillEntity> selected = selectedSkills(command.skillIds(), true);
        validateLinks(selected, teamId, userId);
        boolean platform = teamId == null;
        WikiDocumentEntity document = documents.save(new WikiDocumentEntity(title, teamId, platform, actor));
        document.setParent(validParent(command.parentId(), teamId, document.getId(), userId), actor);
        WikiDocumentRevisionEntity revision = revisions.save(new WikiDocumentRevisionEntity(document, 1, content, sha256(content), actor));
        document.setCurrentRevision(revision, actor); documents.save(document);
        saveLinks(document, selected);
        if (platform && promotionStore != null) promotionStore.publishDirect(document, userId);
        audit.success("WIKI_DOCUMENT_CREATED", actor, "WIKI_DOCUMENT", document.getId(), requestId, null, Map.of(), Map.of());
        events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(document.getId(), com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));
        return view(document);
    }

    @Transactional
    public DocumentView update(Long id, UpdateCommand command, Long userId, String requestId) {
        WikiDocumentEntity document = editable(id, userId); IamUserEntity actor = user(userId);
        if (document.getVersionNo() != command.versionNo()) throw error("OPTIMISTIC_LOCK_CONFLICT", "Resource version is stale", HttpStatus.CONFLICT);
        if (command.skillIds() != null) {
            List<SkillEntity> selected = selectedSkills(command.skillIds(), true);
            validateLinks(selected, document.getTeamId(), userId);
            replaceLinks(document, selected);
        }
        String value = content(command.markdownContent()); document.update(title(command.title()), actor);
        if (command.parentSpecified()) document.setParent(validParent(command.parentId(), document.getTeamId(), id, userId), actor);
        int next = document.getCurrentRevision() == null ? 1 : document.getCurrentRevision().getRevisionNo() + 1;
        WikiDocumentRevisionEntity revision = revisions.save(new WikiDocumentRevisionEntity(document, next, value, sha256(value), actor));
        document.setCurrentRevision(revision, actor); documents.saveAndFlush(document);
        if (document.getTeamId() == null && promotionStore != null) promotionStore.publishDirect(document, userId);
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
        if (document.getTeamId() == null && promotionStore != null) promotionStore.publishDirect(document, userId);
        audit.success("WIKI_DOCUMENT_RESTORED", actor, "WIKI_DOCUMENT", id, requestId, null, Map.of("sourceRevisionId", revisionId, "revisionNo", next), Map.of());
        events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(id, com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));
        return view(document);
    }

    @Transactional
    public void archive(Long id, Long userId, String requestId) {
        WikiDocumentEntity root = visibleDocument(id, userId);
        if (!canArchive(root, userId)) throw error("WIKI_ARCHIVE_FORBIDDEN", "Only the creator or a team administrator may archive this Wiki tree", HttpStatus.FORBIDDEN);
        if (!isAdmin() && !isTeamAdmin(root.getTeamId(), userId) && hasOtherAuthorsInTree(root, userId, new HashSet<>()))
            throw error("WIKI_TREE_HAS_OTHER_AUTHORS", "A team administrator must archive a tree containing other authors' documents", HttpStatus.CONFLICT);
        IamUserEntity actor = user(userId);
        archiveTree(root, actor, requestId, new HashSet<>());
    }

    private void archiveTree(WikiDocumentEntity document, IamUserEntity actor, String requestId, Set<Long> visited) {
        if (!visited.add(document.getId())) return;
        for (WikiDocumentEntity child : documents.findByParentIdAndStatusOrderByIdAsc(document.getId(), "ACTIVE"))
            archiveTree(child, actor, requestId, visited);
        if (reviewRepository != null) reviewRepository.findByDocumentIdAndStatus(document.getId(), com.company.skillplatform.wiki.domain.WikiReviewStatus.PENDING)
                .ifPresent(review -> { review.withdraw(actor, "Wiki 已下架", java.time.Instant.now()); reviewRepository.save(review); if (promotionStore != null) promotionStore.release(review.getId()); });
        document.archive(actor);
        documents.save(document);
        audit.success("WIKI_DOCUMENT_ARCHIVED", actor, "WIKI_DOCUMENT", document.getId(), requestId, null, Map.of(), Map.of());
        events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(document.getId(), com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.DELETE));
    }

    private boolean hasOtherAuthorsInTree(WikiDocumentEntity document, Long userId, Set<Long> visited) {
        if (!visited.add(document.getId())) return false;
        for (WikiDocumentEntity child : documents.findByParentIdAndStatusOrderByIdAsc(document.getId(), "ACTIVE")) {
            if (!Objects.equals(child.getCreatedBy().getId(), userId) || hasOtherAuthorsInTree(child, userId, visited)) return true;
        }
        return false;
    }

    private WikiDocumentEntity validParent(Long parentId, Long teamId, Long documentId, Long userId) {
        if (parentId == null) return null;
        if (parentId.equals(documentId)) throw error("WIKI_PARENT_CYCLE", "Wiki cannot be its own parent", HttpStatus.BAD_REQUEST);
        WikiDocumentEntity parent = documents.findByIdForUpdate(parentId).filter(d -> "ACTIVE".equals(d.getStatus()) && isVisible(d, userId))
                .orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Parent Wiki not found", HttpStatus.NOT_FOUND));
        boolean scopeMatches = Objects.equals(parent.getTeamId(), teamId);
        if (!scopeMatches)
            throw error("WIKI_PARENT_SCOPE_INVALID", "Parent Wiki must belong to the same team", HttpStatus.BAD_REQUEST);
        for (WikiDocumentEntity ancestor = parent; ancestor != null; ancestor = ancestor.getParent())
            if (ancestor.getId().equals(documentId)) throw error("WIKI_PARENT_CYCLE", "Wiki parent cycle", HttpStatus.BAD_REQUEST);
        return parent;
    }

    @Transactional(readOnly = true)
    public List<PromotionDocument> validatePromotion(Long skillId, Long teamId, List<Long> requestedIds) {
        List<WikiDocumentEntity> linked = links.findBySkillId(skillId).stream().map(WikiDocumentSkillEntity::getDocument).filter(d -> "ACTIVE".equals(d.getStatus())).toList();
        List<WikiDocumentEntity> selected = new ArrayList<>();
        for (Long id : requestedIds == null ? List.<Long>of() : requestedIds) {
            WikiDocumentEntity document = documents.findById(id).orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND));
            if (!Objects.equals(document.getTeamId(), teamId) || links.findByDocumentIdOrderBySortOrderAsc(id).stream().noneMatch(l -> l.getSkill().getId().equals(skillId))) throw error("WIKI_PROMOTION_DOCUMENT_INVALID", "Selected Wiki document cannot be promoted with this Skill", HttpStatus.BAD_REQUEST);
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
        return hasAuthority("wiki:edit") && document.getTeamId() != null && isActiveMember(document.getTeamId(), userId);
    }
    private boolean canArchive(WikiDocumentEntity document, Long userId) {
        return isAdmin() || (document.getTeamId() != null && isActiveMember(document.getTeamId(), userId)
                && (isTeamAdmin(document.getTeamId(), userId) || Objects.equals(document.getCreatedBy().getId(), userId)));
    }

    private Specification<WikiDocumentEntity> visibility(Long userId) {
        if (isAdmin()) return (root, q, cb) -> cb.equal(root.get("status"), "ACTIVE");
        List<Long> teamIds = members.findByUserId(userId).stream().filter(m -> "ACTIVE".equals(m.getStatus()) && "ACTIVE".equals(m.getTeam().getStatus())).map(m -> m.getTeam().getId()).toList();
        return (root, q, cb) -> {
            var active = cb.equal(root.get("status"), "ACTIVE");
            var approved = q.subquery(Long.class); var snapshot = approved.from(WikiPlatformSnapshotEntity.class);
            approved.select(snapshot.get("documentId"));
            var published = cb.and(cb.isTrue(root.get("platformVisible")), root.get("id").in(approved));
            if (teamIds.isEmpty()) return cb.and(active, published);
            return cb.and(active, cb.or(published, root.get("teamId").in(teamIds)));
        };
    }
    private WikiDocumentEntity visibleDocument(Long id, Long userId) { return documents.findById(id).filter(d -> "ACTIVE".equals(d.getStatus())).filter(d -> isVisible(d, userId)).orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND)); }
    private boolean isVisible(WikiDocumentEntity d, Long userId) { return isAdmin() || isActiveMember(d.getTeamId(), userId) || d.isPlatformVisible() && (promotionStore == null || promotionStore.platform(d.getId()) != null); }
    private WikiDocumentEntity editable(Long id, Long userId) { WikiDocumentEntity d = visibleDocument(id, userId); if (d.getTeamId() != null && d.isPlatformVisible() && (promotionStore == null || promotionStore.platform(id) == null)) throw error("WIKI_PLATFORM_SNAPSHOT_REQUIRED", "Published Wiki editing requires an approved platform snapshot", HttpStatus.CONFLICT); if (!canEdit(d, userId)) throw error("WIKI_EDIT_FORBIDDEN", "You cannot edit this Wiki document", HttpStatus.FORBIDDEN); return d; }
    private List<SkillEntity> selectedSkills(List<Long> ids, boolean allowEmpty) {
        if (ids == null || ids.isEmpty()) {
            if (allowEmpty) return List.of();
            throw error("WIKI_SKILLS_REQUIRED", "At least one unique Skill is required", HttpStatus.BAD_REQUEST);
        }
        if (ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size())
            throw error("WIKI_SKILLS_REQUIRED", "At least one unique Skill is required", HttpStatus.BAD_REQUEST);
        List<SkillEntity> found = skills.findAllById(ids);
        if (found.size() != ids.size()) throw error("SKILL_NOT_FOUND", "A linked Skill was not found", HttpStatus.NOT_FOUND);
        Map<Long,SkillEntity> byId=found.stream().collect(Collectors.toMap(SkillEntity::getId, skill->skill));
        return ids.stream().map(byId::get).toList();
    }
    private void validateLinks(List<SkillEntity> selected, Long teamId, Long userId) {
        for (SkillEntity skill : selected) {
            if (skill.getStatus() != SkillStatus.ACTIVE)
                throw error("WIKI_INACTIVE_SKILL_LINK", "A Wiki cannot link an inactive Skill", HttpStatus.BAD_REQUEST);
            if ("TEAM".equals(skill.getScopeType()) && !Objects.equals(teamId, skill.getTeamId()))
                throw error("WIKI_CROSS_TEAM_SKILL", "A team Wiki cannot link another team's private Skill", HttpStatus.BAD_REQUEST);
            if ("TEAM".equals(skill.getScopeType()) && !isActiveMember(skill.getTeamId(), userId) && !isAdmin())
                throw error("WIKI_SKILL_NOT_VISIBLE", "Linked Skill is not visible", HttpStatus.FORBIDDEN);
            if (!"TEAM".equals(skill.getScopeType()) && !"PLATFORM".equals(skill.getScopeType()))
                throw error("WIKI_SKILL_SCOPE_INVALID", "Unsupported Skill scope", HttpStatus.BAD_REQUEST);
        }
    }
    private void saveLinks(WikiDocumentEntity document, List<SkillEntity> selected) { int i = 0; for (SkillEntity skill : selected) links.save(new WikiDocumentSkillEntity(document, skill, "RELATED", i++)); }
    private void replaceLinks(WikiDocumentEntity document, List<SkillEntity> selected) {
        Map<Long, WikiDocumentSkillEntity> existing = links.findByDocumentIdOrderBySortOrderAsc(document.getId()).stream()
                .collect(Collectors.toMap(link -> link.getSkill().getId(), link -> link));
        Set<Long> requested = selected.stream().map(SkillEntity::getId).collect(Collectors.toSet());
        existing.values().stream().filter(link -> !requested.contains(link.getSkill().getId())).forEach(links::delete);
        int order = 0;
        for (SkillEntity skill : selected) {
            WikiDocumentSkillEntity link = existing.get(skill.getId());
            if (link == null) links.save(new WikiDocumentSkillEntity(document, skill, "RELATED", order));
            else link.setSortOrder(order);
            order++;
        }
        links.flush();
    }
    private DocumentView view(WikiDocumentEntity d) {
        Long actorId = currentUserId();
        boolean teamAudience = isAdmin() || isActiveMember(d.getTeamId(), actorId);
        WikiPromotionStore.PlatformSnapshot approved = !teamAudience && promotionStore != null ? promotionStore.platform(d.getId()) : null;
        if (!teamAudience && approved == null && d.getTeamId() != null)
            throw error("WIKI_DOCUMENT_NOT_FOUND", "Wiki document not found", HttpStatus.NOT_FOUND);
        List<SkillLinkView> skillViews = approved == null
                ? links.findByDocumentIdOrderBySortOrderAsc(d.getId()).stream().map(l -> new SkillLinkView(l.getSkill().getId(), l.getSkill().getSkillKey(), l.getSkill().getDisplayName())).toList()
                : promotionStore.platformSkills(d.getId()).stream().map(link -> new SkillLinkView(link.skillId(), link.skillKey(), link.displayName())).toList();
        WikiDocumentRevisionEntity revision = d.getCurrentRevision();
        WikiDocumentReviewEntity pending = teamAudience ? pendingReview(d.getId()) : null;
        boolean canManage = teamAudience && canEdit(d, actorId) && (!d.isPlatformVisible() || promotionStore == null || promotionStore.platform(d.getId()) != null);
        return new DocumentView(d.getId(), approved == null ? d.getTitle() : approved.title(), d.getTeamId(), d.isPlatformVisible(),
                approved == null ? revision == null ? "" : revision.getMarkdownContent() : approved.markdownContent(),
                approved == null ? revision == null ? 0 : revision.getRevisionNo() : approved.revisionNo(), teamAudience ? d.getVersionNo() : 0, skillViews,
                canManage, "ACTIVE".equals(d.getStatus()), pending == null ? null : pending.getId(), pending == null ? null : pending.getStatus().name(),
                approved == null ? d.getParent() == null ? null : d.getParent().getId() : approved.parentId(), teamAudience ? d.getSourceResourceId() : null, teamAudience && canArchive(d, actorId));
    }
    private TeamView teamView(com.company.skillplatform.user.infrastructure.entity.OrgTeamEntity t) { return new TeamView(t.getId(), t.getTeamName(), t.getParent() == null ? null : t.getParent().getId()); }
    private Pageable limited(Pageable p) { return PageRequest.of(p.getPageNumber(), Math.min(Math.max(p.getPageSize(), 1), 50), p.getSort().isSorted() ? p.getSort() : Sort.by("timeUpdated").descending()); }
    private String title(String value) { if (value == null || value.isBlank() || value.length() > 255) throw error("WIKI_TITLE_INVALID", "Title must contain 1..255 characters", HttpStatus.BAD_REQUEST); return value.trim(); }
    private String content(String value) { if (value == null || value.isBlank() || value.length() > MAX_CONTENT_CHARS) throw error("WIKI_MARKDOWN_INVALID", "Markdown must contain 1..1000000 characters", HttpStatus.BAD_REQUEST); return value; }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private Long currentUserId() { Authentication a = SecurityContextHolder.getContext().getAuthentication(); return a == null || !(a.getPrincipal() instanceof Long) ? null : (Long) a.getPrincipal(); }
    private boolean hasPendingReview(Long documentId) { return reviewRepository != null && reviewRepository.existsByDocumentIdAndStatus(documentId, com.company.skillplatform.wiki.domain.WikiReviewStatus.PENDING); }
    private WikiDocumentReviewEntity pendingReview(Long documentId) { return reviewRepository == null ? null : reviewRepository.findByDocumentIdAndStatus(documentId, com.company.skillplatform.wiki.domain.WikiReviewStatus.PENDING).orElse(null); }
    private boolean isAdmin() { Authentication a = SecurityContextHolder.getContext().getAuthentication(); return a != null && a.getAuthorities().stream().anyMatch(x -> "admin:identity".equals(x.getAuthority())); }
    private boolean hasAuthority(String permission) { Authentication a = SecurityContextHolder.getContext().getAuthentication(); return a != null && a.getAuthorities().stream().anyMatch(x -> permission.equals(x.getAuthority())); }
    private void requireAuthority(String permission) { if (!isAdmin() && !hasAuthority(permission)) throw error("WIKI_PERMISSION_REQUIRED", "Wiki permission required", HttpStatus.FORBIDDEN); }
    private boolean isTeamAdmin(Long teamId, Long userId) { return teamId != null && userId != null && scopedRoles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(userId, "TEAM_ADMIN", "TEAM", teamId).isPresent(); }
    private boolean isActiveMember(Long teamId, Long userId) { return teamId != null && userId != null && members.findByUserId(userId).stream().anyMatch(m -> "ACTIVE".equals(m.getStatus()) && "ACTIVE".equals(m.getTeam().getStatus()) && teamId.equals(m.getTeam().getId())); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> error("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND)); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record CreateCommand(String title, Long teamId, List<Long> skillIds, String markdownContent, Long parentId) {
        public CreateCommand(String title, Long teamId, List<Long> skillIds, String markdownContent) { this(title, teamId, skillIds, markdownContent, null); }
    }
    public record UpdateCommand(String title, String markdownContent, int versionNo, List<Long> skillIds, Long parentId, boolean parentSpecified) {
        public UpdateCommand(String title, String markdownContent, int versionNo, List<Long> skillIds) { this(title, markdownContent, versionNo, skillIds, null, false); }
    }
    public record TeamView(Long id, String name, Long parentId) {}
    public record SkillLinkView(Long id, String skillKey, String displayName) {}
    public record DocumentView(Long id, String title, Long teamId, boolean platformVisible, String markdownContent, int revisionNo, int versionNo, List<SkillLinkView> skills, boolean canEdit, boolean active, Long pendingPlatformReviewId, String pendingPlatformReviewStatus, Long parentId, String sourceResourceId, boolean canArchive) {}
    public record DocumentMatchView(Long id, String title, int revisionNo, boolean canEdit) {}
    public record RevisionView(Long id, int revisionNo, String markdownContent, Long createdBy, String createdByName, java.time.Instant createdAt) {}
    public record PromotionDocument(WikiDocumentEntity document, WikiDocumentRevisionEntity revision) {}
    public record AgentDocumentView(Long id, String title, int revisionNo, String markdownContent) {}
}
