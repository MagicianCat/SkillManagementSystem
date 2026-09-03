package com.company.skillplatform.skill.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity @Table(name="skill_category")
public class SkillCategoryEntity extends BaseJpaEntity {
    @Column(name="category_key",nullable=false,length=64) private String categoryKey;
    @Column(name="category_name",nullable=false,length=128) private String categoryName;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="parent_id") private SkillCategoryEntity parent;
    @Column(name="sort_order",nullable=false) private int sortOrder;
    @Column(nullable=false,length=32) private String status;
    @Version @Column(name="version_no",nullable=false) private int versionNo;
    protected SkillCategoryEntity() {}
    public String getCategoryKey(){return categoryKey;} public String getCategoryName(){return categoryName;}
    public SkillCategoryEntity getParent(){return parent;} public int getSortOrder(){return sortOrder;} public String getStatus(){return status;}
}
