package com.company.skillplatform.wiki.application;

import com.company.skillplatform.audit.application.AuditService;
import com.company.skillplatform.knowledge.application.WikiKnowledgeChangedEvent;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared, atomic publication work for reviewed and direct team Wiki promotion. */
@Service
public class WikiPublicationService {
    private final WikiDocumentRepository documents;
    private final SkillRepository skills;
    private final WikiPromotionStore promotionStore;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    public WikiPublicationService(WikiDocumentRepository documents, SkillRepository skills,
                                  WikiPromotionStore promotionStore, ApplicationEventPublisher events, AuditService audit) {
        this.documents = documents; this.skills = skills; this.promotionStore = promotionStore;
        this.events = events; this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long publish(WikiPromotionStore.Tree tree, String source, Long reviewId, IamUserEntity actor,
                        String comment, String requestId, Instant now) {
        long publicationId = promotionStore.recordPublication(source, tree.rootId(), tree.teamId(), reviewId,
                actor.getId(), tree.fingerprint(), tree.items().size(), tree.skills().size(),
                (int) tree.skills().stream().filter(skill -> "TEAM".equals(skill.scope())).count(), comment, now);
        for (WikiPromotionStore.SkillItem skill : tree.skills()) if ("TEAM".equals(skill.scope())) skill.skill().promoteToPlatform(actor);
        var promoted = tree.skills().stream().filter(skill -> "TEAM".equals(skill.scope())).map(WikiPromotionStore.SkillItem::skill).toList();
        if (!promoted.isEmpty()) skills.saveAll(promoted);
        for (WikiPromotionStore.SkillItem skill : tree.skills()) if ("TEAM".equals(skill.scope()))
            audit.success("SKILL_PROMOTED_VIA_WIKI_TREE", actor, "SKILL", skill.skillId(), requestId,
                    Map.of("scopeType", "TEAM"), Map.of("scopeType", "PLATFORM", "promotionSource", source,
                            "publicationId", publicationId, "rootWikiId", tree.rootId(), "originTeamId", tree.teamId(),
                            "approvedVersionId", skill.versionId()), Map.of());
        for (WikiPromotionStore.Item item : tree.items()) item.document().publishToPlatform(actor);
        documents.saveAllAndFlush(tree.items().stream().map(WikiPromotionStore.Item::document).toList());
        promotionStore.publishSnapshot(tree, source, reviewId, actor.getId(), now, publicationId);
        for (WikiPromotionStore.Item item : tree.items())
            events.publishEvent(new WikiKnowledgeChangedEvent(item.documentId(), WikiKnowledgeChangedEvent.Operation.UPSERT));
        audit.success("WIKI_PLATFORM_PUBLISHED", actor, "WIKI_DOCUMENT", tree.rootId(), requestId,
                Map.of("source", source), Map.of("publicationId", publicationId, "wikiCount", tree.items().size(), "skillCount", tree.skills().size()), Map.of());
        return publicationId;
    }
}
