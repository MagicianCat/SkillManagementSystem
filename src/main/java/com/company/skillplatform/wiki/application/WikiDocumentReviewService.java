package com.company.skillplatform.wiki.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.notification.application.NotificationService;
import com.company.skillplatform.skill.domain.SkillStatus;
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
    private final OrgTeamRepository teams;
    private final ScopedRoleAssignmentRepository scopedRoles;
    private final IamUserRepository users;
    private final AuditService audit;
    private final Clock clock;
    private NotificationService notifications;

    @org.springframework.beans.factory.annotation.Autowired
    public WikiDocumentReviewService(WikiDocumentReviewRepository reviews, WikiDocumentRepository documents,
                                     WikiDocumentSkillRepository links, OrgTeamRepository teams,
                                     ScopedRoleAssignmentRepository scopedRoles, IamUserRepository users,
                                     AuditService audit) {
        this(reviews, documents, links, teams, scopedRoles, users, audit, Clock.systemUTC());
    }

    WikiDocumentReviewService(WikiDocumentReviewRepository reviews, WikiDocumentRepository documents,
                              WikiDocumentSkillRepository links, OrgTeamRepository teams,
                              ScopedRoleAssignmentRepository scopedRoles, IamUserRepository users,
                              AuditService audit, Clock clock) {
        this.reviews = reviews; this.documents = documents; this.links = links; this.teams = teams;
        this.scopedRoles = scopedRoles; this.users = users; this.audit = audit; this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setNotifications(NotificationService notifications) { this.notifications = notifications; }

    @PreAuthorize("hasAuthority('skill:edit')")
    @Transactional
    public ReviewView submit(Long documentId, int versionNo, String comment, Long actorId, String requestId) {
        WikiDocumentEntity document = document(documentId);
        if (document.getVersionNo() != versionNo) throw conflict("OPTIMISTIC_LOCK_CONFLICT");
        if (!canManage(document, actorId)) throw error("WIKI_EDIT_FORBIDDEN", "You cannot submit this Wiki document", HttpStatus.FORBIDDEN);
        validateCandidate(document);
        if (reviews.existsByDocumentIdAndStatus(documentId, WikiReviewStatus.PENDING)) throw conflict("WIKI_REVIEW_PENDING");
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_REVIEW_COMMENT_INVALID", "Review comment must contain 1..2000 characters", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId);
        WikiDocumentRevisionEntity revision = document.getCurrentRevision();
        WikiDocumentReviewEntity review = reviews.save(new WikiDocumentReviewEntity(document, revision,
                (int) reviews.countByDocumentId(documentId) + 1, actor, comment.trim(), clock.instant()));
        audit.success("WIKI_PLATFORM_REVIEW_SUBMITTED", actor, "WIKI_DOCUMENT", documentId, requestId,
                Map.of("platformVisible", false), Map.of("reviewId", review.getId(), "revisionNo", revision.getRevisionNo()), Map.of());
        if (notifications != null) notifications.wikiReviewSubmitted(document, review.getId());
        return view(review, false);
    }

    @PreAuthorize("hasAuthority('wiki:review')")
    @Transactional(readOnly = true)
    public Page<ReviewSummary> list(WikiReviewStatus status, String keyword, Long teamId, Pageable pageable) {
        Specification<WikiDocumentReviewEntity> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (teamId != null) predicates.add(cb.equal(root.get("document").get("teamId"), teamId));
            if (keyword != null && !keyword.isBlank()) predicates.add(cb.like(cb.lower(root.get("document").get("title")), "%" + keyword.toLowerCase(Locale.ROOT) + "%"));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return reviews.findAll(spec, pageable).map(this::summary);
    }

    @PreAuthorize("hasAuthority('wiki:review')")
    @Transactional(readOnly = true)
    public ReviewView get(Long id) { return view(anyReview(id), true); }

    @PreAuthorize("hasAuthority('wiki:review')")
    @Transactional
    public ReviewView approve(Long id, String comment, Long actorId, String requestId) {
        WikiDocumentReviewEntity review = pending(id);
        WikiDocumentEntity document = review.getDocument();
        validateCandidate(document);
        if (document.getCurrentRevision() == null || !document.getCurrentRevision().getId().equals(review.getRevision().getId())) throw conflict("WIKI_REVIEW_STALE");
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_REVIEW_COMMENT_INVALID", "Review comment must contain 1..2000 characters", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId);
        review.approve(actor, comment.trim(), clock.instant());
        document.publishToPlatform(actor);
        documents.saveAndFlush(document);
        reviews.save(review);
        audit.success("WIKI_PLATFORM_REVIEW_APPROVED", actor, "WIKI_DOCUMENT", document.getId(), requestId,
                Map.of("platformVisible", false), Map.of("platformVisible", true, "reviewId", id), Map.of());
        if (notifications != null) notifications.wikiReviewCompleted(document, review.getId(), true, review.getSubmitter().getId(), comment);
        return view(review, false);
    }

    @PreAuthorize("hasAuthority('wiki:review')")
    @Transactional
    public ReviewView reject(Long id, String comment, Long actorId, String requestId) {
        WikiDocumentReviewEntity review = pending(id);
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_REVIEW_COMMENT_INVALID", "Review comment must contain 1..2000 characters", HttpStatus.BAD_REQUEST);
        IamUserEntity actor = user(actorId);
        review.reject(actor, comment.trim(), clock.instant());
        reviews.save(review);
        audit.success("WIKI_PLATFORM_REVIEW_REJECTED", actor, "WIKI_DOCUMENT", review.getDocument().getId(), requestId,
                Map.of("reviewId", id), Map.of("status", WikiReviewStatus.REJECTED.name()), Map.of());
        if (notifications != null) notifications.wikiReviewCompleted(review.getDocument(), review.getId(), false, review.getSubmitter().getId(), comment);
        return view(review, false);
    }

    @PreAuthorize("hasAuthority('skill:edit')")
    @Transactional
    public ReviewView withdraw(Long id, String comment, Long actorId, String requestId) {
        WikiDocumentReviewEntity review = pending(id);
        if (!canManage(review.getDocument(), actorId)) throw error("WIKI_EDIT_FORBIDDEN", "You cannot withdraw this Wiki review", HttpStatus.FORBIDDEN);
        IamUserEntity actor = user(actorId);
        review.withdraw(actor, comment == null ? "" : comment.trim(), clock.instant());
        reviews.save(review);
        audit.success("WIKI_PLATFORM_REVIEW_WITHDRAWN", actor, "WIKI_DOCUMENT", review.getDocument().getId(), requestId,
                Map.of("reviewId", id), Map.of("status", WikiReviewStatus.CANCELLED.name()), Map.of());
        return view(review, false);
    }

    @PreAuthorize("hasAuthority('wiki:review')")
    @Transactional
    public BatchReviewView batch(List<Long> ids, String comment, Long actorId, String requestId, boolean approve) {
        if (ids == null || ids.isEmpty() || ids.size() > 50 || new HashSet<>(ids).size() != ids.size()) throw error("WIKI_REVIEW_IDS_INVALID", "reviewIds must contain 1..50 unique values", HttpStatus.BAD_REQUEST);
        List<BatchReviewItem> items = new ArrayList<>();
        for (Long id : ids) {
            try { items.add(new BatchReviewItem(id, true, approve( id, comment, actorId, requestId), null, null)); }
            catch (BusinessException ex) { items.add(new BatchReviewItem(id, false, null, ex.getCode(), ex.getMessage())); }
        }
        int success = (int) items.stream().filter(BatchReviewItem::success).count();
        return new BatchReviewView(items.size(), success, items.size() - success, items);
    }

    private void validateCandidate(WikiDocumentEntity document) {
        if (!"SKILL_GUIDE".equals(document.getDocumentType())) throw error("WIKI_PLATFORM_GUIDE_REQUIRED", "Only team Wiki guides can be promoted", HttpStatus.BAD_REQUEST);
        if (document.getTeamId() == null) throw conflict("WIKI_ALREADY_PLATFORM_VISIBLE");
        if (document.isPlatformVisible()) throw conflict("WIKI_ALREADY_PLATFORM_VISIBLE");
        if (!"ACTIVE".equals(document.getStatus()) || document.getCurrentRevision() == null) throw conflict("WIKI_DOCUMENT_INVALID");
        if (links.findByDocumentIdOrderBySortOrderAsc(document.getId()).isEmpty()) throw error("WIKI_SKILLS_REQUIRED", "Wiki must link at least one Skill", HttpStatus.BAD_REQUEST);
        if (links.findByDocumentIdOrderBySortOrderAsc(document.getId()).stream().anyMatch(link -> link.getSkill().getStatus() != SkillStatus.ACTIVE || !"PLATFORM".equals(link.getSkill().getScopeType()))) throw error("WIKI_PRIVATE_SKILL_LINK", "A platform Wiki cannot expose a private or inactive Skill", HttpStatus.BAD_REQUEST);
    }

    private boolean canManage(WikiDocumentEntity document, Long userId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = auth != null && auth.getAuthorities().stream().anyMatch(a -> "admin:identity".equals(a.getAuthority()));
        return admin || (document.getTeamId() != null && scopedRoles.findByUserIdAndRoleKeyAndScopeTypeAndTeamId(userId, "TEAM_ADMIN", "TEAM", document.getTeamId()).isPresent());
    }

    private WikiDocumentReviewEntity anyReview(Long id) { return reviews.findById(id).orElseThrow(() -> notFound("WIKI_REVIEW_NOT_FOUND")); }
    private WikiDocumentReviewEntity pending(Long id) { WikiDocumentReviewEntity review = anyReview(id); if (review.getStatus() != WikiReviewStatus.PENDING) throw conflict("WIKI_REVIEW_NOT_PENDING"); return review; }
    private WikiDocumentEntity document(Long id) { return documents.findById(id).orElseThrow(() -> notFound("WIKI_DOCUMENT_NOT_FOUND")); }
    private IamUserEntity user(Long id) { return users.findById(id).orElseThrow(() -> notFound("USER_NOT_FOUND")); }
    private ReviewSummary summary(WikiDocumentReviewEntity review) { WikiDocumentEntity document = review.getDocument(); return new ReviewSummary(review.getId(), document.getId(), document.getTitle(), document.getTeamId(), teamName(document.getTeamId()), review.getRevision().getRevisionNo(), review.getReviewNo(), review.getStatus(), review.getSubmitter().getId(), review.getSubmitter().getDisplayName(), review.getSubmittedAt(), review.getReviewer() == null ? null : review.getReviewer().getId(), review.getReviewer() == null ? null : review.getReviewer().getDisplayName(), review.getReviewedAt(), review.getSubmitComment(), review.getReviewComment()); }
    private ReviewView view(WikiDocumentReviewEntity review, boolean includeContent) { ReviewSummary summary = summary(review); List<SkillLinkView> skills = links.findByDocumentIdOrderBySortOrderAsc(review.getDocument().getId()).stream().map(l -> new SkillLinkView(l.getSkill().getId(), l.getSkill().getSkillKey(), l.getSkill().getDisplayName())).toList(); return new ReviewView(summary.reviewId(), summary.documentId(), summary.title(), summary.teamId(), summary.teamName(), summary.revisionNo(), summary.reviewNo(), summary.status(), summary.submitterId(), summary.submitterName(), summary.submittedAt(), summary.reviewerId(), summary.reviewerName(), summary.reviewedAt(), summary.submitComment(), summary.reviewComment(), includeContent ? review.getRevision().getMarkdownContent() : null, skills); }
    private String teamName(Long teamId) { return teamId == null ? null : teams.findById(teamId).map(t -> t.getTeamName()).orElse(null); }
    private BusinessException conflict(String code) { return new BusinessException(code, code, HttpStatus.CONFLICT); }
    private BusinessException notFound(String code) { return new BusinessException(code, code, HttpStatus.NOT_FOUND); }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }

    public record ReviewSummary(Long reviewId, Long documentId, String title, Long teamId, String teamName, int revisionNo, int reviewNo, WikiReviewStatus status, Long submitterId, String submitterName, java.time.Instant submittedAt, Long reviewerId, String reviewerName, java.time.Instant reviewedAt, String submitComment, String reviewComment) {}
    public record SkillLinkView(Long id, String skillKey, String displayName) {}
    public record ReviewView(Long reviewId, Long documentId, String title, Long teamId, String teamName, int revisionNo, int reviewNo, WikiReviewStatus status, Long submitterId, String submitterName, java.time.Instant submittedAt, Long reviewerId, String reviewerName, java.time.Instant reviewedAt, String submitComment, String reviewComment, String markdownContent, List<SkillLinkView> skills) {}
    public record BatchReviewItem(Long reviewId, boolean success, ReviewView review, String errorCode, String errorMessage) {}
    public record BatchReviewView(int total, int successCount, int failureCount, List<BatchReviewItem> items) {}
}
