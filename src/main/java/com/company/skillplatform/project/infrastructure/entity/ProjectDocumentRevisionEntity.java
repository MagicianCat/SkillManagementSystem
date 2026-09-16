package com.company.skillplatform.project.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "project_document_revision", uniqueConstraints = @UniqueConstraint(name = "uk_project_document_revision", columnNames = {"document_id", "revision_no"}))
public class ProjectDocumentRevisionEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "document_id", nullable = false) private ProjectDocumentEntity document;
    @Column(name = "revision_no", nullable = false) private int revisionNo;
    @Lob @Column(name = "markdown_content", nullable = false, columnDefinition = "mediumtext") private String markdownContent;
    @Column(name = "content_sha256", nullable = false, columnDefinition = "char(64)") private String contentSha256;
    @Column(name = "source_type", nullable = false, length = 32) private String sourceType;
    @Column(name = "profile_key", length = 64) private String profileKey;
    @Column(name = "agent_session_id", length = 128) private String agentSessionId;
    @Column(name = "agent_job_id", length = 128) private String agentJobId;
    @Lob @Column(name = "skill_snapshots", columnDefinition = "json") private String skillSnapshots;
    @Lob @Column(name = "assumptions", columnDefinition = "json") private String assumptions;
    @Lob @Column(name = "open_questions", columnDefinition = "json") private String openQuestions;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false) private IamUserEntity createdBy;
    protected ProjectDocumentRevisionEntity() {}
    public ProjectDocumentRevisionEntity(ProjectDocumentEntity document, int no, String content, String sha, String sourceType, String profileKey, String sessionId, String jobId, String snapshots, String assumptions, String questions, IamUserEntity actor) {
        this.document = document; this.revisionNo = no; this.markdownContent = content; this.contentSha256 = sha; this.sourceType = sourceType; this.profileKey = profileKey; this.agentSessionId = sessionId; this.agentJobId = jobId; this.skillSnapshots = snapshots; this.assumptions = assumptions; this.openQuestions = questions; this.createdBy = actor;
    }
    public ProjectDocumentEntity getDocument() { return document; } public int getRevisionNo() { return revisionNo; }
    public String getMarkdownContent() { return markdownContent; } public String getContentSha256() { return contentSha256; }
    public String getSourceType() { return sourceType; } public String getProfileKey() { return profileKey; }
    public String getAgentSessionId() { return agentSessionId; } public String getAgentJobId() { return agentJobId; }
    public String getSkillSnapshots() { return skillSnapshots; } public String getAssumptions() { return assumptions; } public String getOpenQuestions() { return openQuestions; }
    public IamUserEntity getCreatedBy() { return createdBy; }
}
