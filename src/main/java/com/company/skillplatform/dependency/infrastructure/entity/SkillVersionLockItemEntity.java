package com.company.skillplatform.dependency.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import jakarta.persistence.*;
@Entity @Table(name="skill_version_lock_item") public class SkillVersionLockItemEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="lock_id")private SkillVersionLockEntity lock;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;@Column(nullable=false)private int depth;
 @Column(name="required_by_path",nullable=false,length=2048)private String requiredByPath;@Column(name="resolved_version",nullable=false,length=32)private String resolvedVersion;
 @Column(nullable=false,columnDefinition="char(64)")private String sha256;protected SkillVersionLockItemEntity(){}
}
