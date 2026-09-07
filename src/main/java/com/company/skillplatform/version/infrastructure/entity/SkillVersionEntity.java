package com.company.skillplatform.version.infrastructure.entity;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.version.domain.ChangeType;
import com.company.skillplatform.version.domain.LifecycleStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.http.HttpStatus;

@Entity @Table(name="skill_version")
public class SkillVersionEntity extends BaseJpaEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="skill_id") private SkillEntity skill;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="base_version_id") private SkillVersionEntity baseVersion;
    @Enumerated(EnumType.STRING) @Column(name="change_type",nullable=false,length=32) private ChangeType changeType;
    @Column(name="candidate_version",length=32) private String candidateVersion;
    @Column(length=32) private String version;
    @Enumerated(EnumType.STRING) @Column(name="lifecycle_status",nullable=false,length=32) private LifecycleStatus lifecycleStatus;
    @Column(name="source_revision",nullable=false) private int sourceRevision;
    @Column(name="source_object_key",nullable=false,length=512) private String sourceObjectKey;
    @Column(name="source_sha256",nullable=false,columnDefinition="char(64)") private String sourceSha256;
    @Column(name="source_size_bytes",nullable=false) private long sourceSizeBytes;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="manifest_json",nullable=false,columnDefinition="json") private Map<String,Object> manifest;
    @Lob @Column(name="change_log",columnDefinition="text") private String changeLog;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="replacement_version_id") private SkillVersionEntity replacementVersion;
    @Column(name="published_at") private Instant publishedAt;
    @Column(name="offline_at") private Instant offlineAt;
    @Version @Column(name="version_no",nullable=false) private int versionNo;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="created_by") private IamUserEntity createdBy;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="updated_by") private IamUserEntity updatedBy;
    protected SkillVersionEntity() {}
    public SkillVersionEntity(SkillEntity skill,SkillVersionEntity base,ChangeType type,IamUserEntity actor){
        this.skill=skill;this.baseVersion=base;this.changeType=type;this.lifecycleStatus=LifecycleStatus.DRAFT;
        this.sourceRevision=0;this.sourceObjectKey="pending";this.sourceSha256="0".repeat(64);this.sourceSizeBytes=0;
        this.manifest=Map.of("storageStatus","PENDING");this.createdBy=actor;this.updatedBy=actor;
    }
    public void markUploaded(String changeLog,IamUserEntity actor){require(LifecycleStatus.DRAFT);sourceRevision++;changeType=ChangeType.ZIP_REUPLOAD;this.changeLog=changeLog;updatedBy=actor;}
    public void markSourceChanged(String changeLog,IamUserEntity actor){require(LifecycleStatus.DRAFT);sourceRevision++;this.changeLog=changeLog;updatedBy=actor;}
    public void submit(String candidate,IamUserEntity actor){require(LifecycleStatus.DRAFT);candidateVersion=candidate;lifecycleStatus=LifecycleStatus.REVIEWING;updatedBy=actor;}
    public void approve(IamUserEntity actor){if(lifecycleStatus==LifecycleStatus.PUBLISHED){updatedBy=actor;return;}require(LifecycleStatus.REVIEWING);lifecycleStatus=LifecycleStatus.APPROVED;updatedBy=actor;}
    public void reject(IamUserEntity actor){require(LifecycleStatus.REVIEWING);lifecycleStatus=LifecycleStatus.DRAFT;updatedBy=actor;}
    public void withdraw(IamUserEntity actor){require(LifecycleStatus.APPROVED);lifecycleStatus=LifecycleStatus.DRAFT;updatedBy=actor;}
    public void publish(Instant now,IamUserEntity actor){require(LifecycleStatus.APPROVED);version=candidateVersion;lifecycleStatus=LifecycleStatus.PUBLISHED;publishedAt=now;updatedBy=actor;}
    public void deprecate(SkillVersionEntity replacement,IamUserEntity actor){require(LifecycleStatus.PUBLISHED);replacementVersion=replacement;lifecycleStatus=LifecycleStatus.DEPRECATED;updatedBy=actor;}
    public void offline(Instant now,IamUserEntity actor){if(lifecycleStatus!=LifecycleStatus.PUBLISHED&&lifecycleStatus!=LifecycleStatus.DEPRECATED)invalid();lifecycleStatus=LifecycleStatus.OFFLINE;offlineAt=now;updatedBy=actor;}
    public void cancel(IamUserEntity actor){require(LifecycleStatus.DRAFT);candidateVersion=null;lifecycleStatus=LifecycleStatus.CANCELLED;updatedBy=actor;}
    private void require(LifecycleStatus expected){if(lifecycleStatus!=expected)invalid();}
    private void invalid(){throw new BusinessException("INVALID_LIFECYCLE_TRANSITION","Lifecycle transition is not allowed",HttpStatus.CONFLICT);}
    public SkillEntity getSkill(){return skill;} public SkillVersionEntity getBaseVersion(){return baseVersion;} public ChangeType getChangeType(){return changeType;}
    public String getCandidateVersion(){return candidateVersion;} public String getVersion(){return version;} public LifecycleStatus getLifecycleStatus(){return lifecycleStatus;}
    public int getSourceRevision(){return sourceRevision;} public int getVersionNo(){return versionNo;} public SkillVersionEntity getReplacementVersion(){return replacementVersion;}
    public String getSourceSha256(){return sourceSha256;} public long getSourceSizeBytes(){return sourceSizeBytes;} public String getSourceObjectKey(){return sourceObjectKey;} public Map<String,Object> getManifest(){return manifest;}
    public void storeSource(String objectKey,String sha256,long size,Map<String,Object> manifest){this.sourceObjectKey=objectKey;this.sourceSha256=sha256;this.sourceSizeBytes=size;this.manifest=manifest;}
    public void assignCandidateVersion(String candidate){if(this.candidateVersion==null)this.candidateVersion=candidate;}
    /** 从 base 版本继承源码指针（共享 objectKey，不物理复制）。仅在 base 已完成首次上传时调用。 */
    public void inheritSourceFrom(SkillVersionEntity base){String baseObjectKey=base.getSourceObjectKey();if(baseObjectKey==null||baseObjectKey.equals("pending"))return;Map<String,Object> baseManifest=base.getManifest();this.sourceRevision=base.getSourceRevision();this.sourceObjectKey=baseObjectKey;this.sourceSha256=base.getSourceSha256();this.sourceSizeBytes=base.getSourceSizeBytes();this.manifest=Map.of("storageStatus","READY","fileCount",baseManifest==null?0:baseManifest.getOrDefault("fileCount",0));}
}
