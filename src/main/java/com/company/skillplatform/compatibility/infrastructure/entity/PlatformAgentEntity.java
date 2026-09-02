package com.company.skillplatform.compatibility.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import jakarta.persistence.*;
@Entity@Table(name="platform_agent")public class PlatformAgentEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="platform_id")private PlatformEntity platform;@Column(name="agent_key",nullable=false,length=64)private String agentKey;
 @Column(name="agent_name",nullable=false,length=128)private String agentName;@Column(nullable=false,length=32)private String status;@Column(name="is_default",nullable=false)private boolean defaultAgent;
 @Version@Column(name="version_no",nullable=false)private int versionNo;protected PlatformAgentEntity(){}public String getAgentKey(){return agentKey;}public String getAgentName(){return agentName;}
}
