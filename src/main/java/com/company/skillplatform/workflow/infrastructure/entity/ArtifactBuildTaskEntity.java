package com.company.skillplatform.workflow.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import jakarta.persistence.*;import java.time.Instant;
@Entity@Table(name="artifact_build_task")public class ArtifactBuildTaskEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;@Column(name="task_type",nullable=false,length=32)private String taskType;
 @Column(nullable=false,length=32)private String status;@Column(name="idempotency_key",nullable=false,length=128)private String idempotencyKey;@Column(name="started_at")private Instant startedAt;
 @Column(name="finished_at")private Instant finishedAt;@Column(name="error_code",length=64)private String errorCode;@Lob@Column(name="error_message",columnDefinition="text")private String errorMessage;
 @Column(name="artifact_object_key",length=512)private String artifactObjectKey;@Column(name="artifact_sha256",length=64)private String artifactSha256;@Column(name="artifact_size_bytes")private Long artifactSizeBytes;@Column(name="artifact_content_type",length=128)private String artifactContentType;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="created_by")private IamUserEntity createdBy;@Version@Column(name="version_no",nullable=false)private int versionNo;protected ArtifactBuildTaskEntity(){}
 public ArtifactBuildTaskEntity(SkillVersionEntity v,String key,IamUserEntity actor,Instant now){version=v;taskType="RELEASE";status="PENDING";idempotencyKey=key;createdBy=actor;}
 public void complete(String objectKey,String sha256,long size,String contentType,Instant now){status="SUCCEEDED";artifactObjectKey=objectKey;artifactSha256=sha256;artifactSizeBytes=size;artifactContentType=contentType;finishedAt=now;}
 public void fail(String code,String message,Instant now){status="FAILED";errorCode=code;errorMessage=message;finishedAt=now;}
 public String getStatus(){return status;}public String getIdempotencyKey(){return idempotencyKey;}public SkillVersionEntity getVersion(){return version;}public String getArtifactObjectKey(){return artifactObjectKey;}public String getArtifactSha256(){return artifactSha256;}public Long getArtifactSizeBytes(){return artifactSizeBytes;}public String getArtifactContentType(){return artifactContentType;}
 public Instant getStartedAt(){return startedAt;}public Instant getFinishedAt(){return finishedAt;}public String getErrorCode(){return errorCode;}public String getErrorMessage(){return errorMessage;}
}
