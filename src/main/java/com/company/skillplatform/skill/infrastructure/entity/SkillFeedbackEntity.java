package com.company.skillplatform.skill.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "skill_feedback", uniqueConstraints = @UniqueConstraint(name = "uk_skill_feedback_user", columnNames = {"skill_id", "user_id"}))
public class SkillFeedbackEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "skill_id", nullable = false) private SkillEntity skill;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @Column(nullable = false) private int rating;
    @Column(columnDefinition = "text") private String comment;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;
    protected SkillFeedbackEntity() {}
    public SkillFeedbackEntity(SkillEntity skill, IamUserEntity user, int rating, String comment) { this.skill=skill; this.user=user; this.rating=rating; this.comment=comment; }
    public void update(int rating, String comment) { this.rating=rating; this.comment=comment; }
    public SkillEntity getSkill(){return skill;} public IamUserEntity getUser(){return user;} public int getRating(){return rating;} public String getComment(){return comment;} public int getVersionNo(){return versionNo;}
}
