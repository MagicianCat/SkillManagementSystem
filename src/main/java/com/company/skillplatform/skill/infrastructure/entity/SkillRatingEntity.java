package com.company.skillplatform.skill.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "skill_rating", uniqueConstraints = @UniqueConstraint(name = "uk_skill_rating_user", columnNames = {"skill_id", "user_id"}))
public class SkillRatingEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "skill_id", nullable = false) private SkillEntity skill;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @Column(nullable = false) private int rating;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;
    protected SkillRatingEntity() {}
    public SkillRatingEntity(SkillEntity skill, IamUserEntity user, int rating) { this.skill=skill; this.user=user; this.rating=rating; }
    public void update(int rating) { this.rating=rating; }
    public Long getId(){return super.getId();} public SkillEntity getSkill(){return skill;} public IamUserEntity getUser(){return user;} public int getRating(){return rating;} public int getVersionNo(){return versionNo;}
}
