package com.company.skillplatform.workflow.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import com.company.skillplatform.workflow.domain.ReviewStatus;import jakarta.persistence.*;import java.time.Instant;
@Entity@Table(name="skill_review")public class SkillReviewEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;@Column(name="review_no",nullable=false)private int reviewNo;
 @Enumerated(EnumType.STRING)@Column(nullable=false,length=32)private ReviewStatus status;@Column(name="review_scope",nullable=false,length=32)private String reviewScope="PLATFORM";@ManyToOne(fetch=FetchType.LAZY)@JoinColumn(name="parent_review_id")private SkillReviewEntity parentReview;@ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="submitter_id")private IamUserEntity submitter;
 @ManyToOne(fetch=FetchType.LAZY)@JoinColumn(name="reviewer_id")private IamUserEntity reviewer;@Lob@Column(name="submit_comment",columnDefinition="text")private String submitComment;
 @Lob@Column(name="review_comment",columnDefinition="text")private String reviewComment;@Column(name="submitted_at",nullable=false)private Instant submittedAt;@Column(name="reviewed_at")private Instant reviewedAt;
 @Column(name="pending_version_id",insertable=false,updatable=false)private Long pendingVersionId;protected SkillReviewEntity(){}
 public SkillReviewEntity(SkillVersionEntity v,int no,IamUserEntity submitter,String comment,Instant now){version=v;reviewNo=no;this.submitter=submitter;submitComment=comment;submittedAt=now;status=ReviewStatus.PENDING;}
 public void approve(IamUserEntity actor,String comment,Instant now){status=ReviewStatus.APPROVED;reviewer=actor;reviewComment=comment;reviewedAt=now;}
 public void reject(IamUserEntity actor,String comment,Instant now){status=ReviewStatus.REJECTED;reviewer=actor;reviewComment=comment;reviewedAt=now;}
 public void assignScope(String scope,SkillReviewEntity parent){reviewScope=scope;parentReview=parent;}
 @PrePersist void initializeScope(){if("PLATFORM".equals(reviewScope)&&version!=null&&"TEAM".equals(version.getSkill().getScopeType()))reviewScope="TEAM";}
 public SkillVersionEntity getVersion(){return version;}public int getReviewNo(){return reviewNo;}public ReviewStatus getStatus(){return status;}public String getSubmitComment(){return submitComment;}public String getReviewComment(){return reviewComment;}
 public IamUserEntity getSubmitter(){return submitter;}public IamUserEntity getReviewer(){return reviewer;}public Instant getSubmittedAt(){return submittedAt;}public Instant getReviewedAt(){return reviewedAt;}
 public String getReviewScope(){return reviewScope;}public SkillReviewEntity getParentReview(){return parentReview;}
}
