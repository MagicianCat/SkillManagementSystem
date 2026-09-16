package com.company.skillplatform.project.infrastructure.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "project_document_source")
public class ProjectDocumentSourceEntity {
    @EmbeddedId private Key key;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @MapsId("documentId") @JoinColumn(name = "document_id") private ProjectDocumentEntity document;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @MapsId("sourceDocumentId") @JoinColumn(name = "source_document_id") private ProjectDocumentEntity sourceDocument;
    @Column(name = "source_revision_id") private Long sourceRevisionId;
    @Column(name = "relation_type", nullable = false, length = 32) private String relationType = "DERIVED_FROM";
    protected ProjectDocumentSourceEntity() {}
    public ProjectDocumentSourceEntity(ProjectDocumentEntity document, ProjectDocumentEntity source, Long revisionId) { this.document = document; this.sourceDocument = source; this.sourceRevisionId = revisionId; this.key = new Key(document.getId(), source.getId()); }
    @Embeddable public record Key(Long documentId, Long sourceDocumentId) implements java.io.Serializable {}
}
