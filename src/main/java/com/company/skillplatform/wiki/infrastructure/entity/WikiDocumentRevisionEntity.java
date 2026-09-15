package com.company.skillplatform.wiki.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "wiki_document_revision")
public class WikiDocumentRevisionEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "document_id") private WikiDocumentEntity document;
    @Column(name = "revision_no", nullable = false) private int revisionNo;
    @Lob @Column(name = "markdown_content", nullable = false, columnDefinition = "mediumtext") private String markdownContent;
    @Column(name = "content_sha256", nullable = false, columnDefinition = "char(64)") private String contentSha256;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private IamUserEntity createdBy;
    protected WikiDocumentRevisionEntity() {}
    public WikiDocumentRevisionEntity(WikiDocumentEntity document, int revisionNo, String content, String sha256, IamUserEntity actor) {
        this.document = document; this.revisionNo = revisionNo; this.markdownContent = content; this.contentSha256 = sha256; this.createdBy = actor;
    }
    public WikiDocumentEntity getDocument() { return document; } public int getRevisionNo() { return revisionNo; }
    public String getMarkdownContent() { return markdownContent; } public String getContentSha256() { return contentSha256; }
    public IamUserEntity getCreatedBy() { return createdBy; }
}
