package com.company.skillplatform.dependency.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.dependency.domain.DependencyType;import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import jakarta.persistence.*;
@Entity @Table(name="skill_version_dependency") public class SkillVersionDependencyEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="dependency_skill_id")private SkillEntity dependencySkill;
 @Column(name="version_constraint",nullable=false,length=128)private String versionConstraint;@Enumerated(EnumType.STRING)@Column(name="dependency_type",nullable=false,length=32)private DependencyType dependencyType;
 @Column(nullable=false)private boolean required;@Column(name="sort_order",nullable=false)private int sortOrder;protected SkillVersionDependencyEntity(){}
 public SkillVersionDependencyEntity(SkillVersionEntity v,SkillEntity s,String c,DependencyType t,boolean r,int o){version=v;dependencySkill=s;versionConstraint=c;dependencyType=t;required=r;sortOrder=o;}
 public SkillVersionEntity getVersion(){return version;}public SkillEntity getDependencySkill(){return dependencySkill;}public String getVersionConstraint(){return versionConstraint;}public DependencyType getDependencyType(){return dependencyType;}public boolean isRequired(){return required;}public int getSortOrder(){return sortOrder;}
}
