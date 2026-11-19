package com.company.skillplatform.wiki.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.domain.UserStatus;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WikiDirectPublicationService {
    private final WikiDocumentRepository documents;
    private final SkillRepository skills;
    private final IamUserRepository users;
    private final WikiDocumentService wiki;
    private final WikiPromotionStore promotionStore;
    private final WikiPublicationService publicationService;
    @PersistenceContext private EntityManager entityManager;

    public WikiDirectPublicationService(WikiDocumentRepository documents, SkillRepository skills, IamUserRepository users,
                                        WikiDocumentService wiki, WikiPromotionStore promotionStore,
                                        WikiPublicationService publicationService) {
        this.documents = documents; this.skills = skills; this.users = users; this.wiki = wiki;
        this.promotionStore = promotionStore; this.publicationService = publicationService;
    }

    @PreAuthorize("hasAuthority('wiki:publish:direct') or hasAuthority('admin:identity')")
    @Transactional
    public PublicationResult publish(Long documentId, int versionNo, String fingerprint, String comment,
                                     Long actorId, String requestId) {
        var actor = users.findById(actorId).orElseThrow(() -> error("USER_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (actor.getStatus() != UserStatus.ACTIVE) throw error("USER_INACTIVE", HttpStatus.FORBIDDEN);
        var visible = documents.findById(documentId).orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (visible.getTeamId() == null || !"ACTIVE".equals(visible.getStatus()) || !wiki.canEdit(visible, actorId))
            throw error("WIKI_PUBLICATION_FORBIDDEN", HttpStatus.FORBIDDEN);
        promotionStore.lockTeam(visible.getTeamId());
        var root = documents.findByIdForUpdate(documentId).orElseThrow(() -> error("WIKI_DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND));
        if (entityManager != null) entityManager.refresh(root);
        if (root.getTeamId() == null || !"ACTIVE".equals(root.getStatus()) || !wiki.canEdit(root, actorId))
            throw error("WIKI_PUBLICATION_FORBIDDEN", HttpStatus.FORBIDDEN);
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw error("WIKI_PUBLICATION_FINGERPRINT_REQUIRED", HttpStatus.BAD_REQUEST);
        if (comment == null || comment.isBlank() || comment.length() > 2000) throw error("WIKI_PUBLICATION_COMMENT_INVALID", HttpStatus.BAD_REQUEST);
        var previous = promotionStore.matchingDirectPublication(documentId, actorId, fingerprint, comment.trim());
        if (previous != null && root.isPlatformVisible() && Objects.equals(promotionStore.currentPublicationId(documentId), previous.publicationId()) &&
                (root.getVersionNo() == versionNo || root.getVersionNo() == versionNo + 1)) {
            var index = promotionStore.publicationIndexStatus(previous.publicationId());
            return new PublicationResult(previous.publicationId(), "PUBLISHED", documentId, root.getTeamId(),
                    previous.wikiCount(), previous.skillCount(), previous.promotedTeamSkillCount(), previous.publishedAt(),
                    index == null ? "PENDING" : index.indexStatus());
        }
        if (root.getVersionNo() != versionNo) throw error("OPTIMISTIC_LOCK_CONFLICT", HttpStatus.CONFLICT);
        for (var ancestor = root.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (promotionStore.hasPendingAncestor(ancestor.getId())) throw error("WIKI_PUBLICATION_REVIEW_CONFLICT", HttpStatus.CONFLICT);
        var initial = promotionStore.collect(root);
        if (!Objects.equals(initial.fingerprint(), fingerprint)) throw error("WIKI_PUBLICATION_STALE", HttpStatus.CONFLICT);
        checkReviewLocks(initial);
        for (var item : initial.items().stream().sorted(Comparator.comparing(WikiPromotionStore.Item::documentId)).toList()) {
            var lockedItem = documents.findByIdForUpdate(item.documentId()).orElseThrow(() -> error("WIKI_PUBLICATION_STALE", HttpStatus.CONFLICT));
            if (entityManager != null) entityManager.refresh(lockedItem);
        }
        for (var skill : initial.skills().stream().sorted(Comparator.comparing(WikiPromotionStore.SkillItem::skillKey)).toList()) {
            var lockedSkill = skills.findBySkillKeyForUpdate(skill.skillKey()).orElseThrow(() -> error("WIKI_PUBLICATION_STALE", HttpStatus.CONFLICT));
            if (entityManager != null) entityManager.refresh(lockedSkill);
        }
        var locked = promotionStore.collect(root);
        if (!Objects.equals(locked.fingerprint(), fingerprint) || root.getVersionNo() != versionNo)
            throw error("WIKI_PUBLICATION_STALE", HttpStatus.CONFLICT);
        checkReviewLocks(locked);
        Instant now = Instant.now();
        long publicationId = publicationService.publish(locked, "DIRECT", null, actor, comment.trim(), requestId, now);
        int promotedSkills = (int) locked.skills().stream().filter(skill -> "TEAM".equals(skill.scope())).count();
        return new PublicationResult(publicationId, "PUBLISHED", locked.rootId(), locked.teamId(),
                locked.items().size(), locked.skills().size(), promotedSkills, now, "PENDING");
    }

    private void checkReviewLocks(WikiPromotionStore.Tree tree) {
        Long reviewId = promotionStore.conflictingReviewId(tree);
        if (reviewId != null) throw new BusinessException("WIKI_PUBLICATION_REVIEW_CONFLICT",
                "A Wiki or linked Skill is pending review", HttpStatus.CONFLICT);
    }
    @Transactional(readOnly = true)
    public WikiPromotionStore.PublicationIndexStatus indexStatus(long publicationId, long actorId) {
        var status = promotionStore.publicationIndexStatus(publicationId);
        if (status == null) throw error("WIKI_PUBLICATION_NOT_FOUND", HttpStatus.NOT_FOUND);
        wiki.get(status.rootWikiId(), actorId, true);
        return status;
    }
    private BusinessException error(String code, HttpStatus status) { return new BusinessException(code, code, status); }
    public record PublicationResult(long publicationId, String status, long rootWikiId, long sourceTeamId,
                                    int wikiCount, int skillCount, int promotedTeamSkillCount,
                                    Instant publishedAt, String indexStatus) {}
}
