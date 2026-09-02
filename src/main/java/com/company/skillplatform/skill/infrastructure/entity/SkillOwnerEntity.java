package com.company.skillplatform.skill.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.skill.domain.OwnerType;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import jakarta.persistence.*;
@Entity @Table(name="skill_owner") public class SkillOwnerEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_id")private SkillEntity skill;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="user_id")private IamUserEntity user;
 @Enumerated(EnumType.STRING)@Column(name="owner_type",nullable=false,length=32)private OwnerType ownerType;
 protected SkillOwnerEntity(){} public SkillOwnerEntity(SkillEntity s,IamUserEntity u,OwnerType t){skill=s;user=u;ownerType=t;}
 public IamUserEntity getUser(){return user;} public OwnerType getOwnerType(){return ownerType;}
}
