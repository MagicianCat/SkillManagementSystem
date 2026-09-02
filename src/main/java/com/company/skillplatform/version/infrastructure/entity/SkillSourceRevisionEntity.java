package com.company.skillplatform.version.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import jakarta.persistence.*;
@Entity @Table(name="skill_source_revision") public class SkillSourceRevisionEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;
 @Column(name="revision_no",nullable=false)private int revisionNo;@Column(name="object_key",nullable=false,length=512)private String objectKey;
 @Column(nullable=false,columnDefinition="char(64)")private String sha256;@Column(name="size_bytes",nullable=false)private long sizeBytes;
 @Column(name="change_summary",length=1024)private String changeSummary;@ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="created_by")private IamUserEntity createdBy;
 protected SkillSourceRevisionEntity(){} public SkillSourceRevisionEntity(SkillVersionEntity v,int no,String summary,IamUserEntity actor){version=v;revisionNo=no;objectKey="pending";sha256="0".repeat(64);changeSummary=summary;createdBy=actor;}
 public int getRevisionNo(){return revisionNo;} public String getObjectKey(){return objectKey;}
}
