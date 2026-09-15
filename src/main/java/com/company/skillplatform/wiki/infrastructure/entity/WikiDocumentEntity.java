package com.company.skillplatform.wiki.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "wiki_document")
public class WikiDocumentEntity extends BaseJpaEntity {
    @Column(nullable = false, length = 255) private String title;
    @Column(name = "document_type", nullable = false, length = 32) private String documentType;
    @Column(name = "team_id") private Long teamId;
    @Column(name = "platform_visible", nullable = false) private boolean platformVisible;
    @Column(nullable = false, length = 32) private String status = "ACTIVE";
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "current_revision_id") private WikiDocumentRevisionEntity currentRevision;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by") private IamUserEntity createdBy;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "updated_by") private IamUserEntity updatedBy;

    protected WikiDocumentEntity() {}
    public WikiDocumentEntity(String title, String type, Long teamId, boolean platformVisible, IamUserEntity actor) {
        this.title = title; this.documentType = type; this.teamId = teamId; this.platformVisible = platformVisible;
        this.createdBy = actor; this.updatedBy = actor;
    }
    public void update(String title, IamUserEntity actor) { this.title = title; this.updatedBy = actor; }
    public void setCurrentRevision(WikiDocumentRevisionEntity revision, IamUserEntity actor) { this.currentRevision = revision; this.updatedBy = actor; }
    public void publishToPlatform(IamUserEntity actor) { this.platformVisible = true; this.updatedBy = actor; }
    public void archive(IamUserEntity actor) { this.status = "ARCHIVED"; this.updatedBy = actor; }
    public String getTitle() { return title; } public String getDocumentType() { return documentType; }
    public Long getTeamId() { return teamId; } public boolean isPlatformVisible() { return platformVisible; }
    public String getStatus() { return status; } public WikiDocumentRevisionEntity getCurrentRevision() { return currentRevision; }
    public int getVersionNo() { return versionNo; } public IamUserEntity getCreatedBy() { return createdBy; }
}
