package com.company.skillplatform.wiki.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.version.domain.LifecycleStatus;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentSkillEntity;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentRepository;
import com.company.skillplatform.wiki.infrastructure.repository.WikiDocumentSkillRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
import org.springframework.stereotype.Service;

/** Persists immutable approval input and the platform audience's approved view. */
@Service
public class WikiPromotionStore {
    private static final int MAX_WIKIS = 10_000;
    private final WikiDocumentRepository documents;
    private final WikiDocumentSkillRepository links;
    private final JdbcTemplate db;

    public WikiPromotionStore(WikiDocumentRepository documents, WikiDocumentSkillRepository links, JdbcTemplate db) {
        this.documents = documents; this.links = links; this.db = db;
    }

    public Tree collect(WikiDocumentEntity root) {
        if (root.getTeamId() == null || !"ACTIVE".equals(root.getStatus()))
            throw error("WIKI_PROMOTION_TEAM_REQUIRED", "Only an active team Wiki can be promoted", HttpStatus.BAD_REQUEST);
        Long teamId = root.getTeamId();
        Map<Long, Item> items = new LinkedHashMap<>();
        Map<Long, SkillItem> skills = new LinkedHashMap<>();
        Deque<WikiDocumentEntity> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            WikiDocumentEntity document = queue.removeFirst();
            if (!visited.add(document.getId())) throw error("WIKI_PARENT_CYCLE", "Wiki tree contains a cycle", HttpStatus.CONFLICT);
            if (visited.size() > MAX_WIKIS) throw error("WIKI_PROMOTION_TREE_TOO_LARGE", "Wiki tree exceeds the promotion limit", HttpStatus.CONFLICT);
            if (!"ACTIVE".equals(document.getStatus()) || !teamId.equals(document.getTeamId()) || document.getCurrentRevision() == null)
                throw error("WIKI_PROMOTION_DOCUMENT_INVALID", "Wiki tree contains an invalid document: " + document.getId(), HttpStatus.CONFLICT);
            WikiDocumentRevisionEntity revision = document.getCurrentRevision();
            Long parentId = document.getParent() == null ? null : document.getParent().getId();
            List<Long> skillIds = new ArrayList<>();
            for (WikiDocumentSkillEntity link : links.findByDocumentIdOrderBySortOrderAsc(document.getId())) {
                SkillEntity skill = link.getSkill();
                skillIds.add(skill.getId());
                if (skill.getStatus() != SkillStatus.ACTIVE || !("PLATFORM".equals(skill.getScopeType()) ||
                        "TEAM".equals(skill.getScopeType()) && teamId.equals(skill.getTeamId())))
                    throw error("WIKI_PRIVATE_SKILL_LINK", "Invalid Skill " + skill.getSkillKey() + " in Wiki " + document.getId(), HttpStatus.BAD_REQUEST);
                var published = "PLATFORM".equals(skill.getScopeType()) ? skill.getLatestPlatformApprovedVersion() : skill.getLatestPublishedVersion();
                if (published == null || published.getLifecycleStatus() != LifecycleStatus.PUBLISHED)
                    throw error("WIKI_SKILL_NOT_PUBLISHED", "Skill has no approved published version: " + skill.getSkillKey(), HttpStatus.BAD_REQUEST);
                skills.putIfAbsent(skill.getId(), new SkillItem(skill.getId(), skill.getSkillKey(), skill.getScopeType(),
                        skill.getTeamId(), published.getId(), skill));
            }
            skillIds.sort(Long::compareTo);
            items.put(document.getId(), new Item(document.getId(), parentId, document.getTitle(), revision.getId(),
                    revision.getRevisionNo(), revision.getContentSha256(), List.copyOf(skillIds), document.getKnowledgeCategory().name(), document));
            for (WikiDocumentEntity child : documents.findByParentIdAndStatusOrderByIdAsc(document.getId(), "ACTIVE")) queue.addLast(child);
        }
        StringBuilder canonical = new StringBuilder();
        items.values().stream().sorted(Comparator.comparing(Item::documentId)).forEach(item -> canonical
                .append(item.documentId()).append(':').append(item.parentId()).append(':').append(item.title()).append(':')
                .append(item.revisionId()).append(':').append(item.contentSha256()).append(':').append(item.skillIds()).append(':').append(item.knowledgeCategory()).append(';'));
        skills.values().stream().sorted(Comparator.comparing(SkillItem::skillId)).forEach(skill -> canonical
                .append(skill.skillId()).append(':').append(skill.scope()).append(':').append(skill.versionId()).append(':')
                .append(skill.skill().getDisplayName()).append(':').append(skill.skill().getDescription()).append(';'));
        return new Tree(root.getId(), teamId, List.copyOf(items.values()), List.copyOf(skills.values()), sha256(canonical.toString()));
    }

    public void savePending(long reviewId, Tree tree) {
        for (Item item : tree.items()) {
            db.update("INSERT INTO wiki_active_review_lock(document_id,review_id) VALUES(?,?)", item.documentId(), reviewId);
            db.update("INSERT INTO wiki_platform_review_item(review_id,document_id,revision_id,parent_id,title_snapshot,content_sha256,hierarchy_path,knowledge_category_snapshot) VALUES(?,?,?,?,?,?,?,?)",
                    reviewId, item.documentId(), item.revisionId(), item.parentId(), item.title(), item.contentSha256(), String.valueOf(item.documentId()), item.knowledgeCategory());
        }
        for (SkillItem skill : tree.skills()) {
            db.update("INSERT INTO wiki_active_skill_review_lock(skill_id,review_id) VALUES(?,?)", skill.skillId(), reviewId);
            db.update("INSERT INTO wiki_platform_review_skill(review_id,skill_id,published_version_id,scope_at_submit,source_team_id,display_name_snapshot) VALUES(?,?,?,?,?,?)",
                    reviewId, skill.skillId(), skill.versionId(), skill.scope(), tree.teamId(), skill.skill().getDisplayName());
        }
    }

    public void publish(long reviewId, Tree tree, long actorId, Instant now) {
        publishSnapshot(tree, "REVIEW", reviewId, actorId, now, null);
        release(reviewId);
    }

    public long recordPublication(String source, long rootId, Long teamId, Long reviewId, long actorId,
                                  String fingerprint, int wikiCount, int skillCount, int promotedTeamSkillCount, String comment, Instant now) {
        var keys = new GeneratedKeyHolder();
        db.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO wiki_publication(source,root_document_id,source_team_id,review_id,actor_id,tree_fingerprint,wiki_count,skill_count,promoted_team_skill_count,`comment`,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, source); statement.setLong(2, rootId); statement.setObject(3, teamId);
            statement.setObject(4, reviewId); statement.setLong(5, actorId); statement.setString(6, fingerprint);
            statement.setInt(7, wikiCount); statement.setInt(8, skillCount); statement.setInt(9, promotedTeamSkillCount);
            statement.setString(10, comment); statement.setTimestamp(11, Timestamp.from(now));
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    public DirectPublication matchingDirectPublication(long rootId, long actorId, String fingerprint, String comment) {
        List<DirectPublication> rows = db.query("SELECT id,wiki_count,skill_count,promoted_team_skill_count,published_at FROM wiki_publication WHERE source='DIRECT' AND root_document_id=? AND actor_id=? AND tree_fingerprint=? AND `comment`=? ORDER BY id DESC LIMIT 1",
                (rs, row) -> new DirectPublication(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getTimestamp(5).toInstant()),
                rootId, actorId, fingerprint, comment);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Long currentPublicationId(long documentId) {
        List<Long> rows = db.queryForList("SELECT publication_id FROM wiki_platform_snapshot WHERE document_id=? AND publication_source='DIRECT'", Long.class, documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Long platformPublicationId(long documentId) {
        List<Long> rows = db.queryForList("SELECT publication_id FROM wiki_platform_snapshot WHERE document_id=?", Long.class, documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public PublicationIndexStatus legacyIndexStatus(long documentId) {
        List<String> states = db.queryForList("SELECT status FROM ops_index_state WHERE document_id=?", String.class, documentId);
        String status = states.isEmpty() ? "PENDING" : "SUCCEEDED".equals(states.get(0)) ? "READY" : "EXHAUSTED".equals(states.get(0)) ? "FAILED" : "PENDING";
        return new PublicationIndexStatus(documentId, status);
    }

    public void publishSnapshot(Tree tree, String source, Long reviewId, long actorId, Instant now, Long publicationId) {
        Timestamp publishedAt = Timestamp.from(now);
        for (Item item : tree.items()) {
            db.update("INSERT INTO wiki_platform_snapshot(document_id,approved_revision_id,approved_title,approved_parent_id,approved_review_id,source_team_id,published_at,published_by,approved_knowledge_category,publication_id,publication_source) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE approved_revision_id=VALUES(approved_revision_id),approved_title=VALUES(approved_title),approved_parent_id=VALUES(approved_parent_id),approved_review_id=VALUES(approved_review_id),source_team_id=VALUES(source_team_id),published_at=VALUES(published_at),published_by=VALUES(published_by),approved_knowledge_category=VALUES(approved_knowledge_category),publication_id=VALUES(publication_id),publication_source=VALUES(publication_source)",
                    item.documentId(), item.revisionId(), item.title(), item.parentId(), reviewId, tree.teamId(), publishedAt, actorId, item.knowledgeCategory(), publicationId, source);
            db.update("DELETE FROM wiki_platform_skill_link WHERE document_id=?", item.documentId());
            for (Long skillId : item.skillIds()) {
                SkillItem skill = tree.skills().stream().filter(value -> Objects.equals(value.skillId(), skillId)).findFirst().orElseThrow();
                db.update("INSERT INTO wiki_platform_skill_link(document_id,skill_id,published_skill_version_id,approved_review_id,published_at,approved_display_name) VALUES(?,?,?,?,?,?)",
                        item.documentId(), skillId, skill.versionId(), reviewId, publishedAt, skill.skill().getDisplayName());
            }
        }
    }

    public void release(long reviewId) { db.update("DELETE FROM wiki_active_review_lock WHERE review_id=?", reviewId); db.update("DELETE FROM wiki_active_skill_review_lock WHERE review_id=?", reviewId); }
    public void lockTeam(long teamId) { db.queryForObject("SELECT id FROM org_team WHERE id=? FOR UPDATE", Long.class, teamId); }
    public boolean hasPendingAncestor(long documentId) { return !db.queryForList("SELECT review_id FROM wiki_active_review_lock WHERE document_id=?", Long.class, documentId).isEmpty(); }
    public Long conflictingReviewId(Tree tree) {
        for (Item item : tree.items()) {
            var rows = db.queryForList("SELECT review_id FROM wiki_active_review_lock WHERE document_id=?", Long.class, item.documentId());
            if (!rows.isEmpty()) return rows.get(0);
        }
        for (SkillItem skill : tree.skills()) {
            var rows = db.queryForList("SELECT review_id FROM wiki_active_skill_review_lock WHERE skill_id=?", Long.class, skill.skillId());
            if (!rows.isEmpty()) return rows.get(0);
        }
        return null;
    }

    public PlatformSnapshot platform(long documentId) {
        List<PlatformSnapshot> rows = db.query("SELECT p.document_id,p.approved_revision_id,p.approved_title,p.approved_parent_id,r.revision_no,r.markdown_content,p.approved_knowledge_category FROM wiki_platform_snapshot p JOIN wiki_document_revision r ON r.id=p.approved_revision_id WHERE p.document_id=?",
                (rs, row) -> new PlatformSnapshot(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long)rs.getObject(4), rs.getInt(5), rs.getString(6), rs.getString(7)), documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Compare the entire current subtree with the public snapshots, including links and hierarchy. */
    public boolean isPublishedTreeCurrent(Tree tree) {
        Map<Long, SkillItem> skillsById = new HashMap<>();
        for (SkillItem skill : tree.skills()) skillsById.put(skill.skillId(), skill);
        Map<Long, Set<Long>> childrenByParent = new HashMap<>();
        for (Item item : tree.items()) {
            if (item.parentId() != null) childrenByParent.computeIfAbsent(item.parentId(), ignored -> new HashSet<>()).add(item.documentId());
        }
        for (Item item : tree.items()) {
            if (!item.document().isPlatformVisible()) return false;
            PlatformSnapshot approved = platform(item.documentId());
            if (approved == null || !Objects.equals(approved.revisionId(), item.revisionId()) ||
                    !Objects.equals(approved.title(), item.title()) || !Objects.equals(approved.parentId(), item.parentId()) ||
                    !Objects.equals(approved.knowledgeCategory(), item.knowledgeCategory())) return false;
            List<PlatformSkillLink> publishedLinks = platformSkills(item.documentId());
            if (publishedLinks.size() != item.skillIds().size()) return false;
            for (PlatformSkillLink link : publishedLinks) {
                SkillItem current = skillsById.get(link.skillId());
                if (!item.skillIds().contains(link.skillId()) || current == null ||
                        !Objects.equals(link.versionId(), current.versionId()) ||
                        !Objects.equals(link.displayName(), current.skill().getDisplayName())) return false;
            }
            if (!childrenByParent.getOrDefault(item.documentId(), Set.of()).equals(new HashSet<>(platformChildren(item.documentId())))) return false;
        }
        return true;
    }

    /** A Wiki created directly at platform scope has no review, but still needs a public snapshot. */
    public void publishDirect(WikiDocumentEntity document, Long actorId) {
        if (document.getTeamId() != null || !document.isPlatformVisible() || document.getCurrentRevision() == null) return;
        long publicationId = recordPublication("NATIVE_PLATFORM", document.getId(), null, null, actorId, null, 1,
                links.findByDocumentIdOrderBySortOrderAsc(document.getId()).size(), 0, null, Instant.now());
        db.update("INSERT INTO wiki_platform_snapshot(document_id,approved_revision_id,approved_title,approved_parent_id,approved_review_id,source_team_id,published_at,published_by,approved_knowledge_category,publication_id,publication_source) VALUES(?,?,?,?,NULL,NULL,NOW(6),?,?,?,'NATIVE_PLATFORM') ON DUPLICATE KEY UPDATE approved_revision_id=VALUES(approved_revision_id),approved_title=VALUES(approved_title),approved_parent_id=VALUES(approved_parent_id),approved_review_id=NULL,published_at=VALUES(published_at),published_by=VALUES(published_by),approved_knowledge_category=VALUES(approved_knowledge_category),publication_id=VALUES(publication_id),publication_source='NATIVE_PLATFORM'",
                document.getId(), document.getCurrentRevision().getId(), document.getTitle(),
                document.getParent() == null ? null : document.getParent().getId(), actorId, document.getKnowledgeCategory().name(), publicationId);
        db.update("DELETE FROM wiki_platform_skill_link WHERE document_id=?", document.getId());
        db.update("INSERT INTO wiki_platform_skill_link(document_id,skill_id,published_skill_version_id,approved_review_id,published_at,approved_display_name) SELECT l.document_id,l.skill_id,s.latest_platform_approved_version_id,NULL,NOW(6),s.display_name FROM wiki_document_skill l JOIN skill s ON s.id=l.skill_id WHERE l.document_id=? AND s.scope_type='PLATFORM' AND s.latest_platform_approved_version_id IS NOT NULL", document.getId());
    }

    public List<PlatformSkillLink> platformSkills(long documentId) {
        return db.query("SELECT s.id,s.skill_key,COALESCE(l.approved_display_name,s.display_name),l.published_skill_version_id FROM wiki_platform_skill_link l JOIN skill s ON s.id=l.skill_id WHERE l.document_id=? ORDER BY s.id",
                (rs,row) -> new PlatformSkillLink(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getLong(4)), documentId);
    }

    public List<Long> platformChildren(long parentId) {
        return db.queryForList("SELECT p.document_id FROM wiki_platform_snapshot p JOIN wiki_document d ON d.id=p.document_id WHERE p.approved_parent_id=? AND d.status='ACTIVE' AND d.platform_visible=1 ORDER BY p.document_id", Long.class, parentId);
    }

    public List<Long> publicDocumentIdsForSkillKey(String skillKey) {
        return db.queryForList("SELECT l.document_id FROM wiki_platform_skill_link l JOIN skill s ON s.id=l.skill_id JOIN wiki_document d ON d.id=l.document_id WHERE s.skill_key=? AND d.status='ACTIVE' AND d.platform_visible=1", Long.class, skillKey);
    }

    public PublicationIndexStatus publicationIndexStatus(long publicationId) {
        List<Long> roots = db.queryForList("SELECT root_document_id FROM wiki_publication WHERE id=?", Long.class, publicationId);
        if (roots.isEmpty()) return null;
        List<Map<String,Object>> states = db.queryForList("SELECT i.status,CASE WHEN i.status='SUCCEEDED' AND i.last_success_at>=DATE_SUB(p.published_at,INTERVAL 1000 MICROSECOND) THEN 1 ELSE 0 END AS ready FROM wiki_platform_snapshot s " +
                "JOIN wiki_publication p ON p.id=s.publication_id LEFT JOIN ops_index_state i ON i.document_id=s.document_id WHERE p.id=?", publicationId);
        String status = states.isEmpty() ? "PENDING" : states.stream().anyMatch(state -> "EXHAUSTED".equals(state.get("status"))) ? "FAILED"
                : states.stream().allMatch(state -> state.get("ready") instanceof Number ready && ready.intValue() == 1) ? "READY" : "PENDING";
        return new PublicationIndexStatus(roots.get(0), status);
    }

    public List<ReviewWikiItem> reviewItems(long reviewId) {
        return db.query("SELECT i.document_id,i.title_snapshot,i.parent_id,i.revision_id,r.revision_no,i.knowledge_category_snapshot FROM wiki_platform_review_item i JOIN wiki_document_revision r ON r.id=i.revision_id WHERE i.review_id=? ORDER BY i.document_id",
                (rs,row)->new ReviewWikiItem(rs.getLong(1),rs.getString(2),rs.getObject(3)==null?null:rs.getLong(3),rs.getLong(4),rs.getInt(5),rs.getString(6)),reviewId);
    }

    public List<ReviewSkillItem> reviewSkills(long reviewId) {
        return db.query("SELECT s.id,s.skill_key,COALESCE(i.display_name_snapshot,s.display_name),i.scope_at_submit,i.published_version_id FROM wiki_platform_review_skill i JOIN skill s ON s.id=i.skill_id WHERE i.review_id=? ORDER BY s.id",
                (rs,row)->new ReviewSkillItem(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),reviewId);
    }

    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
    public record Item(Long documentId, Long parentId, String title, Long revisionId, int revisionNo, String contentSha256, List<Long> skillIds, String knowledgeCategory, WikiDocumentEntity document) {
        public Item(Long documentId, Long parentId, String title, Long revisionId, int revisionNo, String contentSha256, List<Long> skillIds, WikiDocumentEntity document) { this(documentId, parentId, title, revisionId, revisionNo, contentSha256, skillIds, "OTHER", document); }
    }
    public record SkillItem(Long skillId, String skillKey, String scope, Long teamId, Long versionId, SkillEntity skill) {}
    public record Tree(Long rootId, Long teamId, List<Item> items, List<SkillItem> skills, String fingerprint) {}
    public record PlatformSnapshot(Long documentId, Long revisionId, String title, Long parentId, int revisionNo, String markdownContent, String knowledgeCategory) {
        public PlatformSnapshot(Long documentId, Long revisionId, String title, Long parentId, int revisionNo, String markdownContent) { this(documentId, revisionId, title, parentId, revisionNo, markdownContent, "OTHER"); }
    }
    public record PlatformSkillLink(Long skillId, String skillKey, String displayName, Long versionId) {}
    public record ReviewWikiItem(Long documentId, String title, Long parentId, Long revisionId, int revisionNo, String knowledgeCategory) {
        public ReviewWikiItem(Long documentId, String title, Long parentId, Long revisionId, int revisionNo) { this(documentId, title, parentId, revisionId, revisionNo, "OTHER"); }
    }
    public record ReviewSkillItem(Long skillId, String skillKey, String displayName, String scopeAtSubmit, Long versionId) {}
    public record PublicationIndexStatus(long rootWikiId, String indexStatus) {}
    public record DirectPublication(long publicationId, int wikiCount, int skillCount, int promotedTeamSkillCount, Instant publishedAt) {}
}
