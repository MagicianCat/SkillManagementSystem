package com.company.skillplatform.telemetry.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "command_usage_event")
public class CommandUsageEventEntity extends BaseJpaEntity {
    @Column(name = "event_uuid", nullable = false, unique = true, length = 64) private String eventUuid;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private IamUserEntity user;
    @Column(name = "command_name", nullable = false, length = 128) private String commandName;
    @Column(name = "req_id", length = 256) private String reqId;
    @Column(name = "req_id_source", length = 32) private String reqIdSource;
    @Column(name = "toolset_name", length = 128) private String toolsetName;
    @Column(name = "asdm_asset_id") private Long asdmAssetId;
    @Column(name = "asdm_mapping_id") private Long asdmMappingId;
    @Column(name = "asdm_first_category_code", length = 64) private String asdmFirstCategoryCode;
    @Column(name = "asdm_development_stage", length = 32) private String asdmDevelopmentStage;
    @Column(name = "asdm_match_status", nullable = false, length = 32) private String asdmMatchStatus = "NO_ASSET";
    @Column(name = "trigger_type", nullable = false, length = 32) private String triggerType;
    @Column(name = "evidence_type", nullable = false, length = 32) private String evidenceType;
    @Column(name = "command_version", length = 64) private String commandVersion;
    @Column(name = "installation_id", length = 64) private String installationId;
    @Column(name = "client_session_id", nullable = false, length = 256) private String clientSessionId;
    @Column(name = "generation_id", nullable = false, length = 256) private String generationId;
    @Column(name = "ai_generation_id") private Long aiGenerationId;
    @Column(name = "project_key", length = 128) private String projectKey;
    @Column(nullable = false, length = 64) private String client;
    @Column(name = "client_version", length = 64) private String clientVersion;
    @Column(name = "invoked_at", nullable = false) private Instant invokedAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;

    protected CommandUsageEventEntity() {}

    public CommandUsageEventEntity(String eventUuid, IamUserEntity user, String commandName, String toolsetName,
            String triggerType, String evidenceType, String commandVersion, String installationId,
            String clientSessionId, String generationId, String projectKey, String client, String clientVersion,
            Instant invokedAt, Instant receivedAt) {
        this.eventUuid = eventUuid; this.user = user; this.commandName = commandName; this.toolsetName = toolsetName;
        this.triggerType = triggerType; this.evidenceType = evidenceType; this.commandVersion = commandVersion;
        this.installationId = installationId; this.clientSessionId = clientSessionId; this.generationId = generationId;
        this.projectKey = projectKey; this.client = client; this.clientVersion = clientVersion;
        this.invokedAt = invokedAt; this.receivedAt = receivedAt;
    }

    public String getEventUuid() { return eventUuid; }
    public Long getUserId() { return user.getId(); }
    public String getCommandName() { return commandName; }
    public String getReqId() { return reqId; }
    public String getReqIdSource() { return reqIdSource; }
    public Long getAsdmAssetId() { return asdmAssetId; }
    public Long getAsdmMappingId() { return asdmMappingId; }
    public String getAsdmFirstCategoryCode() { return asdmFirstCategoryCode; }
    public String getAsdmDevelopmentStage() { return asdmDevelopmentStage; }
    public String getAsdmMatchStatus() { return asdmMatchStatus; }
    public void setAsdmResolution(Long assetId, Long mappingId, String firstCategoryCode,
                                  String developmentStage, String matchStatus) {
        this.asdmAssetId = assetId; this.asdmMappingId = mappingId;
        this.asdmFirstCategoryCode = firstCategoryCode; this.asdmDevelopmentStage = developmentStage;
        this.asdmMatchStatus = matchStatus;
    }
    public boolean enrichReqId(String nextReqId, String nextSource) {
        if (nextReqId == null || nextReqId.isBlank() || reqId != null) return false;
        reqId = nextReqId; reqIdSource = nextSource; return true;
    }
    public Instant getInvokedAt() { return invokedAt; }
    public boolean sameIdentity(Long userId, String command, String session, String generation) {
        return getUserId().equals(userId) && commandName.equals(command)
                && clientSessionId.equals(session) && generationId.equals(generation);
    }
}
