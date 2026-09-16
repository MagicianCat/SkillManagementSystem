package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "project_document")
public class ProjectDocumentEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private VirtualProjectEntity project;
    @Column(name = "document_type", nullable = false, length = 32) private String documentType;
    @Column(nullable = false, length = 255) private String title;
    @Column(nullable = false, length = 32) private String status = "DRAFT";
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "current_draft_revision_id") private ProjectDocumentRevisionEntity currentDraftRevision;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "published_revision_id") private ProjectDocumentRevisionEntity publishedRevision;
    @Column(name = "ever_published", nullable = false) private boolean everPublished;
    @Column(name = "last_draft_activity_at") private Instant lastDraftActivityAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false) private IamUserEntity createdBy;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by", nullable = false) private IamUserEntity updatedBy;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;
    protected ProjectDocumentEntity() {}
    public ProjectDocumentEntity(VirtualProjectEntity project, String type, String title, IamUserEntity actor, Instant now) {
        this.project = project; this.documentType = type; this.title = title; this.createdBy = actor; this.updatedBy = actor; this.lastDraftActivityAt = now;
    }
    public void updateTitle(String value, IamUserEntity actor) { this.title = value; this.updatedBy = actor; }
    public void draft(ProjectDocumentRevisionEntity revision, IamUserEntity actor, Instant now) { this.currentDraftRevision = revision; this.updatedBy = actor; this.lastDraftActivityAt = now; this.status = "DRAFT"; }
    public void publish(ProjectDocumentRevisionEntity revision, IamUserEntity actor) { this.publishedRevision = revision; this.everPublished = true; this.status = "PUBLISHED"; this.updatedBy = actor; }
    public void archive(IamUserEntity actor) { this.status = "ARCHIVED"; this.updatedBy = actor; }
    public VirtualProjectEntity getProject() { return project; } public String getDocumentType() { return documentType; }
    public String getTitle() { return title; } public String getStatus() { return status; }
    public ProjectDocumentRevisionEntity getCurrentDraftRevision() { return currentDraftRevision; }
    public ProjectDocumentRevisionEntity getPublishedRevision() { return publishedRevision; }
    public boolean isEverPublished() { return everPublished; } public Instant getLastDraftActivityAt() { return lastDraftActivityAt; }
    public IamUserEntity getCreatedBy() { return createdBy; } public int getVersionNo() { return versionNo; }
}
