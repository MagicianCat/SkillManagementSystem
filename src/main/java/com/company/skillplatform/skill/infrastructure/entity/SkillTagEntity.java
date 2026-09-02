package com.company.skillplatform.skill.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;

@Entity @Table(name="skill_tag")
public class SkillTagEntity extends BaseJpaEntity {
    @Column(name="tag_key",nullable=false,length=64) private String tagKey;
    @Column(name="tag_name",nullable=false,length=128) private String tagName;
    protected SkillTagEntity() {}
    public String getTagKey(){return tagKey;} public String getTagName(){return tagName;}
}
