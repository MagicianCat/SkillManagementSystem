package com.company.skillplatform.dependency.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import jakarta.persistence.*;import java.util.Map;import org.hibernate.annotations.JdbcTypeCode;import org.hibernate.type.SqlTypes;
@Entity @Table(name="skill_version_lock") public class SkillVersionLockEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="root_skill_version_id")private SkillVersionEntity rootVersion;
 @Column(name="lock_hash",nullable=false,columnDefinition="char(64)")private String lockHash;@Column(name="resolver_version",nullable=false,length=32)private String resolverVersion;
 @JdbcTypeCode(SqlTypes.JSON)@Column(name="lock_json",nullable=false,columnDefinition="json")private Map<String,Object> lockJson;protected SkillVersionLockEntity(){}
}
