package com.company.skillplatform.workflow.infrastructure.entity;
import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;import com.company.skillplatform.version.infrastructure.entity.SkillVersionEntity;import jakarta.persistence.*;import java.time.Instant;
@Entity@Table(name="artifact_build_task")public class ArtifactBuildTaskEntity extends BaseJpaEntity{
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="skill_version_id")private SkillVersionEntity version;@Column(name="task_type",nullable=false,length=32)private String taskType;
 @Column(nullable=false,length=32)private String status;@Column(name="idempotency_key",nullable=false,length=128)private String idempotencyKey;@Column(name="started_at")private Instant startedAt;
 @Column(name="finished_at")private Instant finishedAt;@Column(name="error_code",length=64)private String errorCode;@Lob@Column(name="error_message",columnDefinition="text")private String errorMessage;
 @ManyToOne(fetch=FetchType.LAZY,optional=false)@JoinColumn(name="created_by")private IamUserEntity createdBy;@Version@Column(name="version_no",nullable=false)private int versionNo;protected ArtifactBuildTaskEntity(){}
 public ArtifactBuildTaskEntity(SkillVersionEntity v,String key,IamUserEntity actor,Instant now){version=v;taskType="RELEASE";status="SUCCEEDED";idempotencyKey=key;createdBy=actor;startedAt=now;finishedAt=now;}
 public String getStatus(){return status;}public String getIdempotencyKey(){return idempotencyKey;}public SkillVersionEntity getVersion(){return version;}
}
