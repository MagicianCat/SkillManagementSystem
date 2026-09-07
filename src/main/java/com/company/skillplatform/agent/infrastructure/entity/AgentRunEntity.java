package com.company.skillplatform.agent.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="agent_run")
public class AgentRunEntity extends BaseJpaEntity {
    @Column(name="run_key", nullable=false, unique=true, columnDefinition="char(36)") private String runKey;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="session_id") private AgentSessionEntity session;
    @Column(name="run_no", nullable=false) private int runNo;
    @Column(nullable=false, length=32) private String status;
    @Column(name="runtime_run_id", length=128) private String runtimeRunId;
    @Column(name="runtime_version", nullable=false, length=64) private String runtimeVersion;
    @Column(name="model_key", nullable=false, length=128) private String modelKey;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="finished_at") private Instant finishedAt;
    @Column(name="input_tokens") private Integer inputTokens;
    @Column(name="output_tokens") private Integer outputTokens;
    @Column(name="error_code", length=64) private String errorCode;
    @Column(name="error_message", length=1024) private String errorMessage;
    @Version @Column(name="version_no", nullable=false) private int versionNo;

    protected AgentRunEntity() {}
    public AgentRunEntity(AgentSessionEntity session, int runNo, String runtimeVersion, String modelKey) {
        this.runKey=UUID.randomUUID().toString(); this.session=session; this.runNo=runNo;
        this.status="PENDING"; this.runtimeVersion=runtimeVersion; this.modelKey=modelKey;
    }
    public void running(String runtimeRunId){this.status="RUNNING";this.runtimeRunId=runtimeRunId;this.startedAt=Instant.now();}
    public void succeeded(){this.status="SUCCEEDED";this.finishedAt=Instant.now();}
    public void failed(String code,String message){this.status="FAILED";this.errorCode=code;this.errorMessage=message;this.finishedAt=Instant.now();}
    public void cancelled(){this.status="CANCELLED";this.finishedAt=Instant.now();}
    public String getRunKey(){return runKey;} public AgentSessionEntity getSession(){return session;} public int getRunNo(){return runNo;} public String getStatus(){return status;}
    public String getRuntimeRunId(){return runtimeRunId;} public String getRuntimeVersion(){return runtimeVersion;} public String getModelKey(){return modelKey;}
    public Instant getStartedAt(){return startedAt;} public Instant getFinishedAt(){return finishedAt;} public String getErrorCode(){return errorCode;} public String getErrorMessage(){return errorMessage;}
}
