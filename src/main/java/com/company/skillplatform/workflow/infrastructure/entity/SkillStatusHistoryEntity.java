package com.company.skillplatform.workflow.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.version.domain.LifecycleStatus;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import jakarta.persistence.*;
@Entity@Table(name="skill_status_history")public class SkillStatusHistoryEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;@Enumerated(EnumType.STRING)@Column(name="from_status",length=32)private LifecycleStatus fromStatus;
 @Enumerated(EnumType.STRING)@Column(name="to_status",nullable=false,length=32)private LifecycleStatus toStatus;@Column(length=1024)private String reason;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="operator_id")private IamUserEntity operator;protected SkillStatusHistoryEntity(){}
 public SkillStatusHistoryEntity(SkillVersionEntity v,LifecycleStatus from,LifecycleStatus to,String reason,IamUserEntity operator){version=v;fromStatus=from;toStatus=to;this.reason=reason;this.operator=operator;}
}
