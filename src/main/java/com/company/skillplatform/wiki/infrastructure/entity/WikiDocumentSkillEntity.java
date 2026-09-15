package com.company.skillplatform.wiki.infrastructure.entity;

import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = "wiki_document_skill")
public class WikiDocumentSkillEntity {
    @EmbeddedId private Id id;
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("documentId") @JoinColumn(name = "document_id") private WikiDocumentEntity document;
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("skillId") @JoinColumn(name = "skill_id") private SkillEntity skill;
    @Column(name = "relation_type", nullable = false, length = 32) private String relationType;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    protected WikiDocumentSkillEntity() {}
    public WikiDocumentSkillEntity(WikiDocumentEntity document, SkillEntity skill, String relationType, int sortOrder) {
        this.document = document; this.skill = skill; this.relationType = relationType; this.sortOrder = sortOrder; this.id = new Id(document.getId(), skill.getId());
    }
    public WikiDocumentEntity getDocument() { return document; } public SkillEntity getSkill() { return skill; }
    @Embeddable public static class Id implements java.io.Serializable {
        private Long documentId; private Long skillId;
        protected Id() {} public Id(Long d, Long s) { documentId=d; skillId=s; }
        @Override public boolean equals(Object other) { if (this == other) return true; if (!(other instanceof Id id)) return false; return Objects.equals(documentId, id.documentId) && Objects.equals(skillId, id.skillId); }
        @Override public int hashCode() { return Objects.hash(documentId, skillId); }
    }
}
