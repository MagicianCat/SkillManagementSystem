package com.company.skillplatform.agentworkflow.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity
@Table(name="agent_profile")
public class AgentProfileEntity extends BaseJpaEntity {
    @Column(nullable=false, unique=true, length=100) private String code;
    @Column(nullable=false, length=100) private String name;
    @Column(nullable=false, length=50) private String category;
    @Column(length=1000) private String description;
    @Column(nullable=false, length=30) private String status="ACTIVE";
    @Column(length=100) private String maintainer;
    @Column(name="source_type", nullable=false, length=20) private String sourceType="USER";
    @Column(name="owner_user_id") private Long ownerUserId;
    @Column(name="is_system_locked", nullable=false) private boolean systemLocked;
    @Column(name="derived_from_profile_version_id") private Long derivedFromProfileVersionId;
    @Column(name="project_id") private Long projectId;
    protected AgentProfileEntity() {}
    public AgentProfileEntity(String code,String name,String category,String description,String maintainer){this.code=code;this.name=name;this.category=category;this.description=description;this.maintainer=maintainer;}
    public String getCode(){return code;} public String getName(){return name;} public String getCategory(){return category;} public String getDescription(){return description;} public String getStatus(){return status;} public String getMaintainer(){return maintainer;} public String getSourceType(){return sourceType;} public Long getOwnerUserId(){return ownerUserId;} public boolean isSystemLocked(){return systemLocked;} public Long getDerivedFromProfileVersionId(){return derivedFromProfileVersionId;}
    public Long getProjectId(){return projectId;}
    public void assignOwner(Long ownerUserId){this.sourceType="USER";this.ownerUserId=ownerUserId;this.systemLocked=false;}
    public void disable(){status="DISABLED";}
}
