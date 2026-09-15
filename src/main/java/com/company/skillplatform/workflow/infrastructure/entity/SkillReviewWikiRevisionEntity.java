package com.company.skillplatform.workflow.infrastructure.entity;

import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentRevisionEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "skill_review_wiki_revision")
public class SkillReviewWikiRevisionEntity {
    @EmbeddedId private Id id;
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("reviewId") @JoinColumn(name = "review_id") private SkillReviewEntity review;
    @ManyToOne(fetch = FetchType.LAZY) @MapsId("documentId") @JoinColumn(name = "document_id") private WikiDocumentEntity document;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "revision_id", nullable = false) private WikiDocumentRevisionEntity revision;
    protected SkillReviewWikiRevisionEntity() {}
    public SkillReviewWikiRevisionEntity(SkillReviewEntity review, WikiDocumentEntity document, WikiDocumentRevisionEntity revision) { this.review=review; this.document=document; this.revision=revision; this.id=new Id(review.getId(),document.getId()); }
    public WikiDocumentEntity getDocument(){return document;} public WikiDocumentRevisionEntity getRevision(){return revision;}
    @Embeddable public static class Id implements java.io.Serializable { private Long reviewId; private Long documentId; protected Id(){} public Id(Long reviewId,Long documentId){this.reviewId=reviewId;this.documentId=documentId;} @Override public boolean equals(Object o){if(this==o)return true;if(!(o instanceof Id other))return false;return java.util.Objects.equals(reviewId,other.reviewId)&&java.util.Objects.equals(documentId,other.documentId);}@Override public int hashCode(){return java.util.Objects.hash(reviewId,documentId);} }
}
