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
                    revision.getRevisionNo(), revision.getContentSha256(), List.copyOf(skillIds), document));
            for (WikiDocumentEntity child : documents.findByParentIdAndStatusOrderByIdAsc(document.getId(), "ACTIVE")) queue.addLast(child);
        }
        StringBuilder canonical = new StringBuilder();
        items.values().stream().sorted(Comparator.comparing(Item::documentId)).forEach(item -> canonical
                .append(item.documentId()).append(':').append(item.parentId()).append(':').append(item.title()).append(':')
                .append(item.revisionId()).append(':').append(item.contentSha256()).append(':').append(item.skillIds()).append(';'));
        skills.values().stream().sorted(Comparator.comparing(SkillItem::skillId)).forEach(skill -> canonical
                .append(skill.skillId()).append(':').append(skill.scope()).append(':').append(skill.versionId()).append(':')
                .append(skill.skill().getDisplayName()).append(':').append(skill.skill().getDescription()).append(';'));
        return new Tree(root.getId(), teamId, List.copyOf(items.values()), List.copyOf(skills.values()), sha256(canonical.toString()));
    }

    public void savePending(long reviewId, Tree tree) {
        for (Item item : tree.items()) {
            db.update("INSERT INTO wiki_active_review_lock(document_id,review_id) VALUES(?,?)", item.documentId(), reviewId);
            db.update("INSERT INTO wiki_platform_review_item(review_id,document_id,revision_id,parent_id,title_snapshot,content_sha256,hierarchy_path) VALUES(?,?,?,?,?,?,?)",
                    reviewId, item.documentId(), item.revisionId(), item.parentId(), item.title(), item.contentSha256(), String.valueOf(item.documentId()));
        }
        for (SkillItem skill : tree.skills()) {
            db.update("INSERT INTO wiki_active_skill_review_lock(skill_id,review_id) VALUES(?,?)", skill.skillId(), reviewId);
            db.update("INSERT INTO wiki_platform_review_skill(review_id,skill_id,published_version_id,scope_at_submit,source_team_id,display_name_snapshot) VALUES(?,?,?,?,?,?)",
                    reviewId, skill.skillId(), skill.versionId(), skill.scope(), tree.teamId(), skill.skill().getDisplayName());
        }
    }

    public void publish(long reviewId, Tree tree, long actorId, Instant now) {
        Timestamp publishedAt = Timestamp.from(now);
        for (Item item : tree.items()) {
            db.update("INSERT INTO wiki_platform_snapshot(document_id,approved_revision_id,approved_title,approved_parent_id,approved_review_id,source_team_id,published_at,published_by) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE approved_revision_id=VALUES(approved_revision_id),approved_title=VALUES(approved_title),approved_parent_id=VALUES(approved_parent_id),approved_review_id=VALUES(approved_review_id),source_team_id=VALUES(source_team_id),published_at=VALUES(published_at),published_by=VALUES(published_by)",
                    item.documentId(), item.revisionId(), item.title(), item.parentId(), reviewId, tree.teamId(), publishedAt, actorId);
            db.update("DELETE FROM wiki_platform_skill_link WHERE document_id=?", item.documentId());
            for (Long skillId : item.skillIds()) {
                SkillItem skill = tree.skills().stream().filter(value -> Objects.equals(value.skillId(), skillId)).findFirst().orElseThrow();
                db.update("INSERT INTO wiki_platform_skill_link(document_id,skill_id,published_skill_version_id,approved_review_id,published_at,approved_display_name) VALUES(?,?,?,?,?,?)",
                        item.documentId(), skillId, skill.versionId(), reviewId, publishedAt, skill.skill().getDisplayName());
            }
        }
        release(reviewId);
    }

    public void release(long reviewId) { db.update("DELETE FROM wiki_active_review_lock WHERE review_id=?", reviewId); db.update("DELETE FROM wiki_active_skill_review_lock WHERE review_id=?", reviewId); }
    public boolean hasPendingAncestor(long documentId) { return !db.queryForList("SELECT review_id FROM wiki_active_review_lock WHERE document_id=?", Long.class, documentId).isEmpty(); }

    public PlatformSnapshot platform(long documentId) {
        List<PlatformSnapshot> rows = db.query("SELECT p.document_id,p.approved_revision_id,p.approved_title,p.approved_parent_id,r.revision_no,r.markdown_content FROM wiki_platform_snapshot p JOIN wiki_document_revision r ON r.id=p.approved_revision_id WHERE p.document_id=?",
                (rs, row) -> new PlatformSnapshot(rs.getLong(1), rs.getLong(2), rs.getString(3), (Long)rs.getObject(4), rs.getInt(5), rs.getString(6)), documentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** A Wiki created directly at platform scope has no review, but still needs a public snapshot. */
    public void publishDirect(WikiDocumentEntity document, Long actorId) {
        if (document.getTeamId() != null || !document.isPlatformVisible() || document.getCurrentRevision() == null) return;
        db.update("INSERT INTO wiki_platform_snapshot(document_id,approved_revision_id,approved_title,approved_parent_id,approved_review_id,source_team_id,published_at,published_by) VALUES(?,?,?,?,NULL,NULL,NOW(6),?) ON DUPLICATE KEY UPDATE approved_revision_id=VALUES(approved_revision_id),approved_title=VALUES(approved_title),approved_parent_id=VALUES(approved_parent_id),published_at=VALUES(published_at),published_by=VALUES(published_by)",
                document.getId(), document.getCurrentRevision().getId(), document.getTitle(),
                document.getParent() == null ? null : document.getParent().getId(), actorId);
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

    public List<ReviewWikiItem> reviewItems(long reviewId) {
        return db.query("SELECT i.document_id,i.title_snapshot,i.parent_id,i.revision_id,r.revision_no FROM wiki_platform_review_item i JOIN wiki_document_revision r ON r.id=i.revision_id WHERE i.review_id=? ORDER BY i.document_id",
                (rs,row)->new ReviewWikiItem(rs.getLong(1),rs.getString(2),rs.getObject(3)==null?null:rs.getLong(3),rs.getLong(4),rs.getInt(5)),reviewId);
    }

    public List<ReviewSkillItem> reviewSkills(long reviewId) {
        return db.query("SELECT s.id,s.skill_key,COALESCE(i.display_name_snapshot,s.display_name),i.scope_at_submit,i.published_version_id FROM wiki_platform_review_skill i JOIN skill s ON s.id=i.skill_id WHERE i.review_id=? ORDER BY s.id",
                (rs,row)->new ReviewSkillItem(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),reviewId);
    }

    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
    public record Item(Long documentId, Long parentId, String title, Long revisionId, int revisionNo, String contentSha256, List<Long> skillIds, WikiDocumentEntity document) {}
    public record SkillItem(Long skillId, String skillKey, String scope, Long teamId, Long versionId, SkillEntity skill) {}
    public record Tree(Long rootId, Long teamId, List<Item> items, List<SkillItem> skills, String fingerprint) {}
    public record PlatformSnapshot(Long documentId, Long revisionId, String title, Long parentId, int revisionNo, String markdownContent) {}
    public record PlatformSkillLink(Long skillId, String skillKey, String displayName, Long versionId) {}
    public record ReviewWikiItem(Long documentId, String title, Long parentId, Long revisionId, int revisionNo) {}
    public record ReviewSkillItem(Long skillId, String skillKey, String displayName, String scopeAtSubmit, Long versionId) {}
}
