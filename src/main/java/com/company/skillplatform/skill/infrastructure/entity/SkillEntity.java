package com.company.skillplatform.skill.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.skill.domain.DevelopmentStage;
import com.company.skillplatform.skill.domain.SkillStatus;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Formula;

@Entity @Table(name="skill")
public class SkillEntity extends BaseJpaEntity {
    @Column(name="skill_key",nullable=false,length=64) private String skillKey;
    @Column(name="display_name",nullable=false,length=128) private String displayName;
    @Column(nullable=false,length=1024) private String description;
    @Column(name="source_url",length=2048) private String sourceUrl;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="category_id",nullable=false) private SkillCategoryEntity category;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private SkillStatus status;
    @Column(name="scope_type",nullable=false,length=32) private String scopeType="PLATFORM";
    @Column(name="team_id") private Long teamId;
    @Formula("(select case coalesce(parent.category_key, leaf.category_key) when 'requirement' then 'REQUIREMENT' when 'product' then 'PRODUCT' when 'architecture' then 'ARCHITECTURE_DESIGN' when 'ui' then 'UI_DESIGN' when 'backend' then 'BACKEND_CODING' when 'frontend' then 'FRONTEND_CODING' when 'security' then 'SECURITY_REVIEW' when 'testing' then 'TESTING' when 'deployment' then 'DEPLOYMENT' end from skill_category leaf left join skill_category parent on parent.id=leaf.parent_id where leaf.id=category_id)")
    @Enumerated(EnumType.STRING) private DevelopmentStage developmentStage;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="latest_published_version_id") private SkillVersionEntity latestPublishedVersion;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="active_draft_version_id") private SkillVersionEntity activeDraftVersion;
    @Version @Column(name="version_no",nullable=false) private int versionNo;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="created_by",nullable=false) private IamUserEntity createdBy;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="updated_by",nullable=false) private IamUserEntity updatedBy;
    protected SkillEntity() {}
    public SkillEntity(String key,String name,String description,SkillCategoryEntity category,IamUserEntity actor){
        this.skillKey=key;this.displayName=name;this.description=description;this.category=category;this.status=SkillStatus.ACTIVE;this.createdBy=actor;this.updatedBy=actor;
    }
    public SkillEntity(String key,String name,String description,String sourceUrl,SkillCategoryEntity category,IamUserEntity actor){
        this(key,name,description,category,actor);this.sourceUrl=sourceUrl;
    }
    public SkillEntity(String key,String name,String description,SkillCategoryEntity category,IamUserEntity actor,DevelopmentStage developmentStage){
        this(key,name,description,category,actor);
    }
    public void update(String name,String description,SkillCategoryEntity category,IamUserEntity actor){this.displayName=name;this.description=description;this.category=category;this.updatedBy=actor;}
    public void update(String name,String description,String sourceUrl,SkillCategoryEntity category,IamUserEntity actor){update(name,description,category,actor);this.sourceUrl=sourceUrl;}
    public void update(String name,String description,SkillCategoryEntity category,IamUserEntity actor,DevelopmentStage ignored){update(name,description,category,actor);}
    public void setActiveDraftVersion(SkillVersionEntity value,IamUserEntity actor){this.activeDraftVersion=value;this.updatedBy=actor;}
    public void publish(SkillVersionEntity value,IamUserEntity actor){this.latestPublishedVersion=value;this.activeDraftVersion=null;this.updatedBy=actor;}
    public void clearDraft(IamUserEntity actor){this.activeDraftVersion=null;this.updatedBy=actor;}
    public void archive(IamUserEntity actor){this.status=SkillStatus.ARCHIVED;this.updatedBy=actor;}
    public void unarchive(IamUserEntity actor){this.status=SkillStatus.ACTIVE;this.updatedBy=actor;}
    public String getSkillKey(){return skillKey;} public String getDisplayName(){return displayName;} public String getDescription(){return description;}
    public SkillStatus getStatus(){return status;} public String getSourceUrl(){return sourceUrl;} public DevelopmentStage getDevelopmentStage(){if(developmentStage!=null)return developmentStage;SkillCategoryEntity root=category;while(root.getParent()!=null)root=root.getParent();return DevelopmentStage.fromRootCategoryKey(root.getCategoryKey());} public SkillVersionEntity getLatestPublishedVersion(){return latestPublishedVersion;}
    public SkillVersionEntity getActiveDraftVersion(){return activeDraftVersion;} public int getVersionNo(){return versionNo;} public SkillCategoryEntity getCategory(){return category;} public String getScopeType(){return scopeType;} public Long getTeamId(){return teamId;} public void assignTeam(Long id){teamId=id;scopeType=id==null?"PLATFORM":"TEAM";}
    public void promoteToPlatform(){scopeType="PLATFORM";}
    public void promoteToPlatform(IamUserEntity actor){scopeType="PLATFORM";teamId=null;updatedBy=actor;}
}
