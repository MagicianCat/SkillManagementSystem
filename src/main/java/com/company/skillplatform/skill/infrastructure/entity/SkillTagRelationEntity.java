package com.company.skillplatform.skill.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import jakarta.persistence.*;
@Entity @Table(name="skill_tag_relation") public class SkillTagRelationEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_id")private SkillEntity skill;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="tag_id")private SkillTagEntity tag;
 protected SkillTagRelationEntity(){} public SkillTagRelationEntity(SkillEntity s,SkillTagEntity t){skill=s;tag=t;} public SkillTagEntity getTag(){return tag;}
}
