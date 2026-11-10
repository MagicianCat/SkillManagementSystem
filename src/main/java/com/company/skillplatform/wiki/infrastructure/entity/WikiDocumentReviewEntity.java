package com.company.skillplatform.wiki.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.wiki.domain.WikiReviewStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "wiki_document_review")
public class WikiDocumentReviewEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "document_id") private WikiDocumentEntity document;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "revision_id") private WikiDocumentRevisionEntity revision;
    @Column(name = "review_no", nullable = false) private int reviewNo;
    @Column(name = "source_team_id") private Long sourceTeamId;
    @Column(name = "tree_fingerprint", columnDefinition = "char(64)") private String treeFingerprint;
    @Column(name = "wiki_count") private Integer wikiCount;
    @Column(name = "skill_count") private Integer skillCount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private WikiReviewStatus status;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "submitter_id") private IamUserEntity submitter;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reviewer_id") private IamUserEntity reviewer;
    @Lob @Column(name = "submit_comment", columnDefinition = "text") private String submitComment;
    @Lob @Column(name = "review_comment", columnDefinition = "text") private String reviewComment;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;

    protected WikiDocumentReviewEntity() {}

    public WikiDocumentReviewEntity(WikiDocumentEntity document, WikiDocumentRevisionEntity revision, int reviewNo,
                                    IamUserEntity submitter, String comment, Instant submittedAt) {
        this.document = document; this.revision = revision; this.reviewNo = reviewNo; this.submitter = submitter;
        this.submitComment = comment; this.submittedAt = submittedAt; this.status = WikiReviewStatus.PENDING;
    }

    public void approve(IamUserEntity actor, String comment, Instant now) { status = WikiReviewStatus.APPROVED; reviewer = actor; reviewComment = comment; reviewedAt = now; }
    public void reject(IamUserEntity actor, String comment, Instant now) { status = WikiReviewStatus.REJECTED; reviewer = actor; reviewComment = comment; reviewedAt = now; }
    public void withdraw(IamUserEntity actor, String comment, Instant now) { status = WikiReviewStatus.CANCELLED; reviewer = actor; reviewComment = comment; reviewedAt = now; }
    public void markStale(IamUserEntity actor, Instant now) { status = WikiReviewStatus.STALE; reviewer = actor; reviewComment = "审核快照已过期"; reviewedAt = now; }
    public void captureTree(Long teamId, String fingerprint, int wikiCount, int skillCount) { this.sourceTeamId = teamId; this.treeFingerprint = fingerprint; this.wikiCount = wikiCount; this.skillCount = skillCount; }
    public Long getId() { return super.getId(); }
    public WikiDocumentEntity getDocument() { return document; }
    public WikiDocumentRevisionEntity getRevision() { return revision; }
    public int getReviewNo() { return reviewNo; }
    public Long getSourceTeamId() { return sourceTeamId; }
    public String getTreeFingerprint() { return treeFingerprint; }
    public Integer getWikiCount() { return wikiCount; }
    public Integer getSkillCount() { return skillCount; }
    public WikiReviewStatus getStatus() { return status; }
    public IamUserEntity getSubmitter() { return submitter; }
    public IamUserEntity getReviewer() { return reviewer; }
    public String getSubmitComment() { return submitComment; }
    public String getReviewComment() { return reviewComment; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
}
