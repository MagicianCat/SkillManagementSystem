package com.company.skillplatform.wiki.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "wiki_platform_snapshot")
public class WikiPlatformSnapshotEntity {
    @Id @Column(name = "document_id") private Long documentId;
    @Column(name = "approved_revision_id") private Long approvedRevisionId;
    @Column(name = "approved_title") private String approvedTitle;
    @Column(name = "approved_parent_id") private Long approvedParentId;
    protected WikiPlatformSnapshotEntity() {}
    public Long getDocumentId() { return documentId; }
    public Long getApprovedRevisionId() { return approvedRevisionId; }
    public String getApprovedTitle() { return approvedTitle; }
    public Long getApprovedParentId() { return approvedParentId; }
}
