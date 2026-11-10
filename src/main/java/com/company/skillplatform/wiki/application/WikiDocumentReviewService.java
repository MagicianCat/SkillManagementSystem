package com.company.skillplatform.wiki.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.*;
import com.company.skillplatform.wiki.domain.WikiReviewStatus;
import com.company.skillplatform.wiki.infrastructure.entity.*;
import com.company.skillplatform.wiki.infrastructure.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiDocumentReviewService {
    private final WikiDocumentReviewRepository reviews;
    private final WikiDocumentRepository documents;
    private final WikiDocumentSkillRepository links;
    private final SkillRepository skills;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final OrgTeamRepository teams;
    private final ScopedRoleAssignmentRepository scopedRoles;
    private final IamUserRepository users;
    private final AuditService audit;
    private final Clock clock;
    private NotificationService notifications;
    private WikiPromotionStore promotionStore;
    private OrgTeamMemberRepository members;
    private org.springframework.transaction.support.TransactionTemplate reviewTransactions;

    @org.springframework.beans.factory.annotation.Autowired
    public WikiDocumentReviewService(WikiDocumentReviewRepository reviews, WikiDocumentRepository documents,
                                     WikiDocumentSkillRepository links, OrgTeamRepository teams,
                                     SkillRepository skills, ScopedRoleAssignmentRepository scopedRoles, IamUserRepository users,
                                     org.springframework.context.ApplicationEventPublisher events,
                                     AuditService audit) {
        this(reviews, documents, links, teams, skills, scopedRoles, users, events, audit, Clock.systemUTC());
    }

    WikiDocumentReviewService(WikiDocumentReviewRepository reviews, WikiDocumentRepository documents,
                              WikiDocumentSkillRepository links, OrgTeamRepository teams,
                              SkillRepository skills, ScopedRoleAssignmentRepository scopedRoles, IamUserRepository users,
                              org.springframework.context.ApplicationEventPublisher events,
                              AuditService audit, Clock clock) {
        this.reviews = reviews; this.documents = documents; this.links = links; this.teams = teams; this.skills = skills;
        this.scopedRoles = scopedRoles; this.users = users; this.events = events; this.audit = audit; this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setNotifications(NotificationService notifications) { this.notifications = notifications; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setPromotionStore(WikiPromotionStore promotionStore) { this.promotionStore = promotionStore; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setMembers(OrgTeamMemberRepository members) { this.members = members; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setTransactionManager(org.springframework.transaction.PlatformTransactionManager manager) { this.reviewTransactions = new org.springframework.transaction.support.TransactionTemplate(manager); }

    @Transactional(readOnly = true)
    public PromotionPreview preview(Long documentId, Long actorId) {
        WikiDocumentEntity document = document(documentId);
        if (!canManage(document, actorId)) throw error("WIKI_EDIT_FORBIDDEN", "Team membership required", HttpStatus.FORBIDDEN);
        WikiPromotionStore.Tree tree = promotionStore.collect(document);
        return new PromotionPreview(tree.rootId(), tree.teamId(), tree.items().size(), tree.skills().size(),
                (int) tree.skills().stream().filter(skill -> "TEAM".equals(skill.scope())).count(), tree.fingerprint(),
                tree.items().stream().map(item -> new WikiPromotionStore.ReviewWikiItem(item.documentId(), item.title(), item.parentId(), item.revisionId(), item.revisionNo())).toList(),
                tree.skills().stream().map(skill -> new WikiPromotionStore.ReviewSkillItem(skill.skillId(), skill.skillKey(), skill.skill().getDisplayName(), skill.scope(), skill.versionId())).toList());
    }

    @PreAuthorize("hasAuthority('wiki:edit') or hasAuthority('admin:identity')")
    @Transactional
    public ReviewView submit(Long documentId, int versionNo, String comment, Long actorId, String requestId) {
        WikiDocumentEntity document = document(documentId);
        if (document.getVersionNo() != versionNo) throw conflict("OPTIMISTIC_LOCK_CONFLICT");
        if (!canManage(document, actorId)) throw error("WIKI_EDIT_FORBIDDEN", "You cannot submit this Wiki document", HttpStatus.FORBIDDEN);
        for (WikiDocumentEntity ancestor = document.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (promotionStore.hasPendingAncestor(ancestor.getId())) throw conflict("WIKI_REVIEW_OVERLAP");
        WikiPromotionStore.Tree tree = promotionStore.collect(document);
        if (reviews.existsByDocumentIdAndStatus(documentId, WikiReviewStatus.PENDING)) throw conflict("WIKI_REVIEW_PENDING");
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_REVIEW_COMMENT_INVALID", "Review comment must contain 1..2000 characters", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId);
        WikiDocumentRevisionEntity revision = document.getCurrentRevision();
        WikiDocumentReviewEntity review = reviews.save(new WikiDocumentReviewEntity(document, revision,
                (int) reviews.countByDocumentId(documentId) + 1, actor, comment.trim(), clock.instant()));
        review.captureTree(tree.teamId(), tree.fingerprint(), tree.items().size(), tree.skills().size());
        reviews.saveAndFlush(review);
        try { promotionStore.savePending(review.getId(), tree); }
        catch (org.springframework.dao.DuplicateKeyException conflict) { throw conflict("WIKI_REVIEW_OVERLAP"); }
        audit.success("WIKI_PLATFORM_REVIEW_SUBMITTED", actor, "WIKI_DOCUMENT", documentId, requestId,
                Map.of("platformVisible", false), Map.of("reviewId", review.getId(), "revisionNo", revision.getRevisionNo()), Map.of());
        if (notifications != null) notifications.wikiReviewSubmitted(document, review.getId());
        return view(review, false);
    }

    @Transactional(readOnly = true)
    public Page<ReviewSummary> list(WikiReviewStatus status, String keyword, Long teamId, Pageable pageable) {
        Set<Long> allowedTeams = reviewableTeamIds(currentUserId());
        if (teamId != null && !allowedTeams.contains(teamId)) throw error("WIKI_REVIEW_FORBIDDEN", "Team review permission required", HttpStatus.FORBIDDEN);
        Specification<WikiDocumentReviewEntity> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(root.get("document").get("teamId").in(allowedTeams));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (teamId != null) predicates.add(cb.equal(root.get("document").get("teamId"), teamId));
            if (keyword != null && !keyword.isBlank()) predicates.add(cb.like(cb.lower(root.get("document").get("title")), "%" + keyword.toLowerCase(Locale.ROOT) + "%"));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return reviews.findAll(spec, pageable).map(this::summary);
    }

    @Transactional(readOnly = true)
    public ReviewView get(Long id) { WikiDocumentReviewEntity review=anyReview(id); requireReviewer(review.getDocument().getTeamId(),currentUserId()); return view(review, true); }

    @Transactional
    public ReviewView approve(Long id, String comment, Long actorId, String requestId) {
        WikiDocumentReviewEntity review = reviews.findByIdForUpdate(id).orElseThrow(() -> notFound("WIKI_REVIEW_NOT_FOUND"));
        requireReviewer(review.getDocument().getTeamId(), actorId);
        if (review.getStatus() == WikiReviewStatus.APPROVED) return view(review, false);
        if (review.getStatus() != WikiReviewStatus.PENDING) throw conflict("WIKI_REVIEW_NOT_PENDING");
        WikiDocumentEntity document = review.getDocument();
        for (WikiPromotionStore.ReviewWikiItem item : promotionStore.reviewItems(id))
            documents.findByIdForUpdate(item.documentId()).orElseThrow(() -> conflict("WIKI_REVIEW_STALE"));
        for (WikiPromotionStore.ReviewSkillItem item : promotionStore.reviewSkills(id))
            skills.findBySkillKeyForUpdate(item.skillKey()).orElseThrow(() -> conflict("WIKI_REVIEW_STALE"));
        WikiPromotionStore.Tree tree;
        try { tree = promotionStore.collect(document); }
        catch (BusinessException stale) { return markStale(review, actorId); }
        if (!Objects.equals(tree.fingerprint(), review.getTreeFingerprint())) return markStale(review, actorId);
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_REVIEW_COMMENT_INVALID", "Review comment must contain 1..2000 characters", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId);
        review.approve(actor, comment.trim(), clock.instant());
        for (WikiPromotionStore.SkillItem skill : tree.skills()) if ("TEAM".equals(skill.scope())) skill.skill().promoteToPlatform(actor);
        List<com.company.skillplatform.skill.infrastructure.entity.SkillEntity> promotedSkills = tree.skills().stream().filter(skill -> "TEAM".equals(skill.scope())).map(WikiPromotionStore.SkillItem::skill).toList();
        if (!promotedSkills.isEmpty()) skills.saveAll(promotedSkills);
        for (WikiPromotionStore.SkillItem promoted : tree.skills()) if ("TEAM".equals(promoted.scope()))
            audit.success("SKILL_PROMOTED_VIA_WIKI_TREE", actor, "SKILL", promoted.skillId(), requestId,
                    Map.of("scopeType", "TEAM"), Map.of("scopeType", "PLATFORM", "promotionSource", "WIKI_TREE_REVIEW",
                            "wikiReviewId", id, "rootWikiId", tree.rootId(), "originTeamId", tree.teamId(), "approvedVersionId", promoted.versionId()), Map.of());
        for (WikiPromotionStore.Item item : tree.items()) item.document().publishToPlatform(actor);
        documents.saveAllAndFlush(tree.items().stream().map(WikiPromotionStore.Item::document).toList());
        promotionStore.publish(id, tree, actorId, clock.instant());
        reviews.save(review);
        for (WikiPromotionStore.Item item : tree.items()) events.publishEvent(new com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent(item.documentId(), com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent.Operation.UPSERT));
        audit.success("WIKI_PLATFORM_REVIEW_APPROVED", actor, "WIKI_DOCUMENT", document.getId(), requestId,
                Map.of("platformVisible", false), Map.of("platformVisible", true, "reviewId", id), Map.of());
        if (notifications != null) notifications.wikiReviewCompleted(document, review.getId(), true, review.getSubmitter().getId(), comment);
        return view(review, false);
    }

    private ReviewView markStale(WikiDocumentReviewEntity review, Long actorId) {
        review.markStale(user(actorId), clock.instant());
        reviews.save(review);
        promotionStore.release(review.getId());
        if (notifications != null) notifications.wikiReviewStale(review.getDocument(), review.getSubmitter().getId());
        return view(review, false);
    }

    @Transactional
    public ReviewView reject(Long id, String comment, Long actorId, String requestId) {
        WikiDocumentReviewEntity review = pending(id);
        requireReviewer(review.getDocument().getTeamId(), actorId);
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_REVIEW_COMMENT_INVALID", "Review comment must contain 1..2000 characters", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId);
        review.reject(actor, comment.trim(), clock.instant());
        reviews.save(review);
        promotionStore.release(id);
        audit.success("WIKI_PLATFORM_REVIEW_REJECTED", actor, "WIKI_DOCUMENT", review.getDocument().getId(), requestId,
                Map.of("reviewId", id), Map.of("status", WikiReviewStatus.REJECTED.name()), Map.of());
        if (notifications != null) notifications.wikiReviewCompleted(review.getDocument(), review.getId(), false, review.getSubmitter().getId(), comment);
        return view(review, false);
    }

    @PreAuthorize("hasAuthority('wiki:edit') or hasAuthority('admin:identity')")
    @Transactional
    public ReviewView withdraw(Long id, String comment, Long actorId, String requestId) {
        WikiDocumentReviewEntity review = pending(id);
        if (!canManage(review.getDocument(), actorId)) throw error("WIKI_EDIT_FORBIDDEN", "You cannot withdraw this Wiki review", HttpStatus.FORBIDDEN);
        IamUserEntity actor = user(actorId);
        review.withdraw(actor, comment == null ? "" : comment.trim(), clock.instant());
        reviews.save(review);
        promotionStore.release(id);
        audit.success("WIKI_PLATFORM_REVIEW_WITHDRAWN", actor, "WIKI_DOCUMENT", review.getDocument().getId(), requestId,
                Map.of("reviewId", id), Map.of("status", WikiReviewStatus.CANCELLED.name()), Map.of());
        return view(review, false);
    }

    public BatchReviewView batch(List<Long> ids, String comment, Long actorId, String requestId, boolean approve) {
        if (ids == null || ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size()) throw error("WIKI_REVIEW_IDS_INVALID", "reviewIds must contain 1..50 unique values", HttpStatus.BAD_REQUEST);
        List<BatchReviewItem> items = new ArrayList<>();
        for (Long id : ids) {
            try { ReviewView result = reviewTransactions.execute(status -> approve ? approve(id, comment, actorId, requestId) : reject(id, comment, actorId, requestId)); items.add(new BatchReviewItem(id, true, result, null, null)); }
            catch (BusinessException ex) { items.add(new BatchReviewItem(id, false, null, ex.getCode(), ex.getMessage())); }
        }
        int success = (int) items.stream().filter(BatchReviewItem::success).count();
        return new BatchReviewView(items.size(), success, items.size() - success, items);
    }

    private boolean canManage(WikiDocumentEntity document, Long userId) {
        return document.getTeamId() != null && isActiveMember(document.getTeamId(), userId);
    }

    private boolean isActiveMember(Long teamId, Long userId) {
        return teamId != null && userId != null && members != null && members.findByUserId(userId).stream()
                .anyMatch(member -> "ACTIVE".equals(member.getStatus()) && "ACTIVE".equals(member.getTeam().getStatus())
                        && teamId.equals(member.getTeam().getId()));
    }

    private void requireReviewer(Long teamId, Long userId) {
        if (teamId == null || !isActiveMember(teamId, userId) ||
                scopedRoles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(userId, "TEAM_ADMIN", "TEAM", teamId).isEmpty())
            throw error("WIKI_REVIEW_FORBIDDEN", "Source team administrator permission required", HttpStatus.FORBIDDEN);
    }

    private Set<Long> reviewableTeamIds(Long userId) {
        if (userId == null) return Set.of();
        Set<Long> active = members == null ? Set.of() : members.findByUserId(userId).stream()
                .filter(member -> "ACTIVE".equals(member.getStatus()) && "ACTIVE".equals(member.getTeam().getStatus()))
                .map(member -> member.getTeam().getId()).collect(java.util.stream.Collectors.toSet());
        return scopedRoles.findByUserId(userId).stream()
                .filter(role -> "TEAM_ADMIN".equals(role.getRoleKey()) && "TEAM".equals(role.getScopeType()) && role.getTeam() != null)
                .map(role -> role.getTeam().getId()).filter(active::contains).collect(java.util.stream.Collectors.toSet());
    }

    private Long currentUserId() { Authentication auth = SecurityContextHolder.getContext().getAuthentication(); return auth != null && auth.getPrincipal() instanceof Long value ? value : null; }

    private WikiDocumentReviewEntity anyReview(Long id) { return reviews.findById(id).orElseThrow(() -> notFound("WIKI_REVIEW_NOT_FOUND")); }
    private WikiDocumentReviewEntity pending(Long id) { WikiDocumentReviewEntity review = anyReview(id); if (review.getStatus() != WikiReviewStatus.PENDING) throw conflict("WIKI_REVIEW_NOT_PENDING"); return review; }
    private WikiDocumentEntity document(Long id) { return documents.findById(id).orElseThrow(() -> notFound("WIKI_DOCUMENT_NOT_FOUND")); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> notFound("USER_NOT_FOUND")); }
    private ReviewSummary summary(WikiDocumentReviewEntity review) { WikiDocumentEntity document = review.getDocument(); String submittedTitle=review.getTreeFingerprint()==null||promotionStore==null?document.getTitle():promotionStore.reviewItems(review.getId()).stream().filter(item->Objects.equals(item.documentId(),document.getId())).map(WikiPromotionStore.ReviewWikiItem::title).findFirst().orElse(document.getTitle()); return new ReviewSummary(review.getId(), document.getId(), submittedTitle, document.getTeamId(), teamName(document.getTeamId()), review.getRevision().getRevisionNo(), review.getReviewNo(), review.getStatus(), review.getSubmitter().getId(), review.getSubmitter().getDisplayName(), review.getSubmittedAt(), review.getReviewer() == null ? null : review.getReviewer().getId(), review.getReviewer() == null ? null : review.getReviewer().getDisplayName(), review.getReviewedAt(), review.getSubmitComment(), review.getReviewComment()); }
    private ReviewView view(WikiDocumentReviewEntity review, boolean includeContent) { ReviewSummary summary = summary(review); List<WikiPromotionStore.ReviewSkillItem> stored = promotionStore == null ? List.of() : promotionStore.reviewSkills(review.getId()); List<SkillLinkView> skills = review.getTreeFingerprint() == null ? links.findByDocumentIdOrderBySortOrderAsc(review.getDocument().getId()).stream().map(l -> new SkillLinkView(l.getSkill().getId(), l.getSkill().getSkillKey(), l.getSkill().getDisplayName())).toList() : stored.stream().map(item -> new SkillLinkView(item.skillId(), item.skillKey(), item.displayName())).toList(); return new ReviewView(summary.reviewId(), summary.documentId(), summary.title(), summary.teamId(), summary.teamName(), summary.revisionNo(), summary.reviewNo(), summary.status(), summary.submitterId(), summary.submitterName(), summary.submittedAt(), summary.reviewerId(), summary.reviewerName(), summary.reviewedAt(), summary.submitComment(), summary.reviewComment(), includeContent ? review.getRevision().getMarkdownContent() : null, skills, review.getWikiCount(), review.getSkillCount(), review.getTreeFingerprint(), promotionStore == null ? List.of() : promotionStore.reviewItems(review.getId()), stored); }
    private String teamName(Long teamId) { return teamId == null ? null : teams.findById(teamId).map(t -> t.getTeamName()).orElse(null); }
    private BusinessException conflict(String code) { return new BusinessException(code, code, HttpStatus.CONFLICT); }
    private BusinessException notFound(String code) { return new BusinessException(code, code, HttpStatus.NOT_FOUND); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record ReviewSummary(Long reviewId, Long documentId, String title, Long teamId, String teamName, int revisionNo, int reviewNo, WikiReviewStatus status, Long submitterId, String submitterName, java.time.Instant submittedAt, Long reviewerId, String reviewerName, java.time.Instant reviewedAt, String submitComment, String reviewComment) {}
    public record SkillLinkView(Long id, String skillKey, String displayName) {}
    public record ReviewView(Long reviewId, Long documentId, String title, Long teamId, String teamName, int revisionNo, int reviewNo, WikiReviewStatus status, Long submitterId, String submitterName, java.time.Instant submittedAt, Long reviewerId, String reviewerName, java.time.Instant reviewedAt, String submitComment, String reviewComment, String markdownContent, List<SkillLinkView> skills, Integer wikiCount, Integer skillCount, String treeFingerprint, List<WikiPromotionStore.ReviewWikiItem> wikiItems, List<WikiPromotionStore.ReviewSkillItem> skillItems) {}
    public record PromotionPreview(Long rootWikiId, Long sourceTeamId, int wikiCount, int skillCount, int teamSkillsToPromote, String treeFingerprint, List<WikiPromotionStore.ReviewWikiItem> wikiItems, List<WikiPromotionStore.ReviewSkillItem> skillItems) {}
    public record BatchReviewItem(Long reviewId, boolean success, ReviewView review, String errorCode, String errorMessage) {}
    public record BatchReviewView(int total, int successCount, int failureCount, List<BatchReviewItem> items) {}
}
