package com.company.skillplatform.skill.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "skill_comment", indexes = @Index(name = "idx_skill_comment_created", columnList = "skill_id,time_created"))
public class SkillCommentEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "skill_id", nullable = false) private SkillEntity skill;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @Column(nullable = false, columnDefinition = "text") private String comment;
    @Version @Column(name = "version_no", nullable = false) private int versionNo;
    protected SkillCommentEntity() {}
    public SkillCommentEntity(SkillEntity skill, IamUserEntity user, String comment) { this.skill=skill; this.user=user; this.comment=comment; }
    public Long getId(){return super.getId();} public SkillEntity getSkill(){return skill;} public IamUserEntity getUser(){return user;} public String getComment(){return comment;} public int getVersionNo(){return versionNo;}
}
