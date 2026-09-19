package com.company.skillplatform.telemetry.infrastructure.entity;

import com.company.skillplatform.common.infrastructure.entity.BaseJpaEntity;
import com.company.skillplatform.telemetry.domain.GenerationStage;
import com.company.skillplatform.telemetry.domain.GenerationStatus;
import com.company.skillplatform.telemetry.domain.TokenQuality;
import com.company.skillplatform.telemetry.domain.TokenSource;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * 一次用户 Prompt 驱动的一轮完整 Agent 任务，是研发效能统计的中心事实。
 * Token / 代码量 / 耗时都属于 Generation，Skill 仅与之关联，避免多 Skill 场景重复汇总。
 */
@Entity
@Table(name = "ai_generation_event")
public class AiGenerationEntity extends BaseJpaEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private IamUserEntity user;

    @Column(name = "client_installation_id", length = 64) private String clientInstallationId;
    @Column(name = "client_session_id", nullable = false, length = 256) private String clientSessionId;
    @Column(name = "hook_generation_id", nullable = false, length = 256) private String hookGenerationId;

    @Column(name = "project_key", length = 128) private String projectKey;
    @Column(name = "project_name", length = 256) private String projectName;
    @Column(name = "project_source", length = 32) private String projectSource;

    @Enumerated(EnumType.STRING) @Column(name = "primary_stage", length = 32) private GenerationStage primaryStage;

    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Column(name = "duration_ms") private Long durationMs;

    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32) private GenerationStatus status;

    @Column(name = "input_tokens") private Long inputTokens;
    @Column(name = "output_tokens") private Long outputTokens;
    @Column(name = "total_tokens") private Long totalTokens;

    @Column(name = "cache_read_tokens") private Long cacheReadTokens;
    @Column(name = "cache_write_tokens") private Long cacheWriteTokens;
    @Column(name = "cache_miss_tokens") private Long cacheMissTokens;

    @Column(name = "thinking_tokens") private Long thinkingTokens;

    @Column(name = "model_call_count") private Integer modelCallCount;
    @Column(name = "last_tokens") private Long lastTokens;

    @Enumerated(EnumType.STRING) @Column(name = "token_source", length = 32) private TokenSource tokenSource;
    @Enumerated(EnumType.STRING) @Column(name = "token_quality", length = 32) private TokenQuality tokenQuality;

    @Column(name = "lines_added", nullable = false) private long linesAdded;
    @Column(name = "lines_deleted", nullable = false) private long linesDeleted;

    @Column(name = "files_created", nullable = false) private int filesCreated;
    @Column(name = "files_modified", nullable = false) private int filesModified;

    @Column(name = "tool_call_count", nullable = false) private int toolCallCount;
    @Column(name = "tool_failure_count", nullable = false) private int toolFailureCount;

    protected AiGenerationEntity() {}

    public AiGenerationEntity(IamUserEntity user, String clientSessionId, String hookGenerationId,
            GenerationStatus status, Instant startedAt) {
        this.user = user;
        this.clientSessionId = clientSessionId;
        this.hookGenerationId = hookGenerationId;
        this.status = status;
        this.startedAt = startedAt;
    }

    /** Upsert：用最终上报的完整统计覆盖当前行。 */
    public void apply(String clientInstallationId, String projectKey, String projectName, String projectSource,
            GenerationStage primaryStage, Instant endedAt, Long durationMs, GenerationStatus status,
            Long inputTokens, Long outputTokens, Long totalTokens, Long cacheReadTokens, Long cacheWriteTokens,
            Long cacheMissTokens, Long thinkingTokens, Integer modelCallCount, Long lastTokens,
            TokenSource tokenSource, TokenQuality tokenQuality, long linesAdded, long linesDeleted,
            int filesCreated, int filesModified, int toolCallCount, int toolFailureCount) {
        this.clientInstallationId = clientInstallationId;
        this.projectKey = projectKey; this.projectName = projectName; this.projectSource = projectSource;
        this.primaryStage = primaryStage;
        this.endedAt = endedAt; this.durationMs = durationMs; this.status = status;
        this.inputTokens = inputTokens; this.outputTokens = outputTokens; this.totalTokens = totalTokens;
        this.cacheReadTokens = cacheReadTokens; this.cacheWriteTokens = cacheWriteTokens; this.cacheMissTokens = cacheMissTokens;
        this.thinkingTokens = thinkingTokens; this.modelCallCount = modelCallCount; this.lastTokens = lastTokens;
        this.tokenSource = tokenSource; this.tokenQuality = tokenQuality;
        this.linesAdded = linesAdded; this.linesDeleted = linesDeleted;
        this.filesCreated = filesCreated; this.filesModified = filesModified;
        this.toolCallCount = toolCallCount; this.toolFailureCount = toolFailureCount;
    }

    public Long getId() { return super.getId(); }
    public IamUserEntity getUser() { return user; }
    public Long getUserId() { return user.getId(); }
    public String getHookGenerationId() { return hookGenerationId; }
    public String getClientSessionId() { return clientSessionId; }
    public GenerationStage getPrimaryStage() { return primaryStage; }
    public GenerationStatus getStatus() { return status; }
    public TokenQuality getTokenQuality() { return tokenQuality; }
    public Instant getStartedAt() { return startedAt; }
}
