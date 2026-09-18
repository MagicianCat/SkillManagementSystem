package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.wiki.infrastructure.entity.WikiDocumentEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "document_agent_job_context")
public class DocumentAgentJobContextEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "job_id", nullable = false) private DocumentAgentJobEntity job;
    @Column(name = "context_kind", nullable = false, length = 32) private String contextKind;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "artifact_id") private ProjectDocumentEntity artifact;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "artifact_revision_id") private ProjectDocumentRevisionEntity artifactRevision;
    @Column(name = "feishu_doc_id", length = 512) private String feishuDocId;
    @Column(name = "feishu_doc_type", length = 32) private String feishuDocType;
    @Column(name = "feishu_title", length = 255) private String feishuTitle;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "wiki_document_id") private WikiDocumentEntity wikiDocument;
    @Column(name = "ordinal_no", nullable = false) private int ordinalNo;

    protected DocumentAgentJobContextEntity() {}
    public static DocumentAgentJobContextEntity artifact(DocumentAgentJobEntity job, ProjectDocumentEntity document, ProjectDocumentRevisionEntity revision, int ordinal) {
        DocumentAgentJobContextEntity value = new DocumentAgentJobContextEntity(); value.job = job; value.contextKind = "PROJECT_ARTIFACT"; value.artifact = document; value.artifactRevision = revision; value.ordinalNo = ordinal; return value;
    }
    public static DocumentAgentJobContextEntity feishu(DocumentAgentJobEntity job, String docId, String docType, String title, int ordinal) {
        DocumentAgentJobContextEntity value = new DocumentAgentJobContextEntity(); value.job = job; value.contextKind = "FEISHU_DOCUMENT"; value.feishuDocId = docId; value.feishuDocType = docType; value.feishuTitle = title; value.ordinalNo = ordinal; return value;
    }
    public static DocumentAgentJobContextEntity wiki(DocumentAgentJobEntity job, WikiDocumentEntity document, int ordinal) {
        DocumentAgentJobContextEntity value = new DocumentAgentJobContextEntity(); value.job = job; value.contextKind = "WIKI_DOCUMENT"; value.wikiDocument = document; value.ordinalNo = ordinal; return value;
    }
    public DocumentAgentJobEntity getJob() { return job; }
    public String getContextKind() { return contextKind; }
    public ProjectDocumentEntity getArtifact() { return artifact; }
    public ProjectDocumentRevisionEntity getArtifactRevision() { return artifactRevision; }
    public String getFeishuDocId() { return feishuDocId; }
    public String getFeishuDocType() { return feishuDocType; }
    public String getFeishuTitle() { return feishuTitle; }
    public WikiDocumentEntity getWikiDocument() { return wikiDocument; }
    public int getOrdinalNo() { return ordinalNo; }
}
