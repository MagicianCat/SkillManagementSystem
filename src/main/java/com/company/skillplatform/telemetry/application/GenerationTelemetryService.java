package com.company.skillplatform.telemetry.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.skill.domain.DevelopmentStage;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.telemetry.domain.FileCategory;
import com.company.skillplatform.telemetry.domain.GenerationStage;
import com.company.skillplatform.telemetry.domain.GenerationStatus;
import com.company.skillplatform.telemetry.domain.TokenQuality;
import com.company.skillplatform.telemetry.domain.TokenSource;
import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationEntity;
import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationFileMetricEntity;
import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationSkillEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.AiGenerationFileMetricRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.AiGenerationRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.AiGenerationSkillRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generation 上报的 Upsert 服务。幂等键 user_id + hook_generation_id：
 * 同一 Generation 重复上传（含 PARTIAL 后补全为 COMPLETED）不产生重复行。
 */
@Service
public class GenerationTelemetryService {
    private final AiGenerationRepository generations;
    private final AiGenerationSkillRepository generationSkills;
    private final AiGenerationFileMetricRepository fileMetrics;
    private final IamUserRepository users;
    private final SkillRepository skills;
    private final SkillUsageEventRepository skillUsageEvents;

    public GenerationTelemetryService(AiGenerationRepository generations, AiGenerationSkillRepository generationSkills,
            AiGenerationFileMetricRepository fileMetrics, IamUserRepository users, SkillRepository skills,
            SkillUsageEventRepository skillUsageEvents) {
        this.generations = generations; this.generationSkills = generationSkills; this.fileMetrics = fileMetrics;
        this.users = users; this.skills = skills; this.skillUsageEvents = skillUsageEvents;
    }

    @Transactional
    public GenerationView upsert(Long userId, UpsertCommand command) {
        if (command.generationId() == null || command.generationId().isBlank())
            throw error("GENERATION_ID_REQUIRED", HttpStatus.BAD_REQUEST);
        if (command.clientSessionId() == null || command.clientSessionId().isBlank())
            throw error("SESSION_ID_REQUIRED", HttpStatus.BAD_REQUEST);
        if (command.startedAt() == null) throw error("STARTED_AT_REQUIRED", HttpStatus.BAD_REQUEST);

        IamUserEntity user = users.findById(userId).orElseThrow(() -> error("USER_NOT_FOUND", HttpStatus.UNAUTHORIZED));
        GenerationStatus status = parseStatus(command.status());
        GenerationStage stage = deriveStage(command.skillKeys());

        AiGenerationEntity generation = generations
                .findByUser_IdAndHookGenerationId(userId, command.generationId())
                .orElseGet(() -> new AiGenerationEntity(user, command.clientSessionId(), command.generationId(), status, command.startedAt()));
        if (generation.getId() != null && !acceptsUpdate(generation, command))
            return new GenerationView(generation.getId(), command.generationId(), generation.getStatus().name(),
                    generation.getPrimaryStage() == null ? null : generation.getPrimaryStage().name());

        TokenUsage u = command.usage();
        CodeMetric c = command.code();
        generation.apply(command.clientInstallationId(), command.projectKey(), command.projectName(), command.projectSource(),
                stage, command.endedAt(), command.durationMs(), status,
                u == null ? null : u.inputTokens(), u == null ? null : u.outputTokens(), u == null ? null : u.totalTokens(),
                u == null ? null : u.cacheReadTokens(), u == null ? null : u.cacheWriteTokens(), u == null ? null : u.cacheMissTokens(),
                u == null ? null : u.thinkingTokens(), u == null ? null : u.modelCallCount(), u == null ? null : u.lastTokens(),
                u == null ? null : parseTokenSource(u.source()), u == null ? null : parseTokenQuality(u.quality()),
                c == null ? 0 : nz(c.linesAdded()), c == null ? 0 : nz(c.linesDeleted()),
                c == null ? 0 : nzi(c.filesCreated()), c == null ? 0 : nzi(c.filesModified()),
                nzi(command.toolCallCount()), nzi(command.toolFailureCount()), command.revision(),
                command.captureQuality(), command.modelId(), command.modelName(), command.observedLog(),
                command.observedStructured(), command.tokenMismatch(), command.codeSource(), command.ingestLagMs(),
                command.durationSource());
        generation = generations.saveAndFlush(generation);

        replaceSkillRelations(generation, command.skillKeys(), command.skillInvocations());
        replaceFileMetrics(generation, command.fileTypes());
        linkSkillUsageEvents(userId, command.generationId());

        return new GenerationView(generation.getId(), command.generationId(), generation.getStatus().name(),
                stage == null ? null : stage.name());
    }

    /** 研发阶段推导：去重后，0 个→null，1 个→该阶段，多个非空阶段→MULTI_STAGE。 */
    private GenerationStage deriveStage(List<String> skillKeys) {
        if (skillKeys == null || skillKeys.isEmpty()) return null;
        Set<GenerationStage> stages = new LinkedHashSet<>();
        for (SkillEntity skill : skills.findBySkillKeyIn(new LinkedHashSet<>(skillKeys))) {
            DevelopmentStage ds = skill.getDevelopmentStage();
            if (ds != null) stages.add(GenerationStage.fromDevelopmentStage(ds));
        }
        if (stages.isEmpty()) return null;
        if (stages.size() == 1) return stages.iterator().next();
        return GenerationStage.MULTI_STAGE;
    }

    private boolean acceptsUpdate(AiGenerationEntity existing, UpsertCommand incoming) {
        if (existing.getCaptureRevision() > 0 && incoming.revision() == null) return false;
        if (incoming.revision() != null && incoming.revision() <= existing.getCaptureRevision()) return false;
        return qualityRank(incoming.captureQuality()) >= qualityRank(existing.getCaptureQuality());
    }

    private int qualityRank(String value) {
        return switch (value == null ? "" : value) {
            case "STRUCTURED" -> 2;
            case "LOG_FINAL" -> 1;
            default -> 0;
        };
    }

    private void replaceSkillRelations(AiGenerationEntity generation, List<String> skillKeys,
                                       List<SkillInvocationMetric> invocations) {
        generationSkills.deleteAll(generationSkills.findByGeneration_Id(generation.getId()));
        // ai_generation_skill has UNIQUE (generation_id, skill_id). Execute deletes before inserts
        // when a higher revision rebuilds the same skill relation.
        generationSkills.flush();
        if (invocations != null) {
            Map<String, List<SkillInvocationMetric>> grouped = new LinkedHashMap<>();
            for (SkillInvocationMetric invocation : invocations)
                if (invocation.skillKey() != null) grouped.computeIfAbsent(invocation.skillKey(), key -> new ArrayList<>()).add(invocation);
            List<AiGenerationSkillEntity> relations = new ArrayList<>();
            for (SkillEntity skill : skills.findBySkillKeyIn(grouped.keySet())) {
                List<SkillInvocationMetric> calls = grouped.get(skill.getSkillKey());
                Instant first = calls.stream().map(SkillInvocationMetric::invokedAt).filter(t -> t != null)
                        .min(Instant::compareTo).orElse(null);
                Long versionId = calls.stream().map(SkillInvocationMetric::skillVersionId).filter(v -> v != null)
                        .findFirst().orElse(null);
                relations.add(new AiGenerationSkillEntity(generation, skill, versionId, first, calls.size()));
            }
            generationSkills.saveAll(relations);
            generationSkills.flush();
            return;
        }
        if (skillKeys == null || skillKeys.isEmpty()) { generationSkills.flush(); return; }
        List<AiGenerationSkillEntity> relations = new ArrayList<>();
        Instant now = Instant.now();
        for (SkillEntity skill : skills.findBySkillKeyIn(new LinkedHashSet<>(skillKeys)))
            relations.add(new AiGenerationSkillEntity(generation, skill, null, now, 1));
        generationSkills.saveAll(relations);
        generationSkills.flush();
    }

    private void replaceFileMetrics(AiGenerationEntity generation, List<FileTypeMetric> fileTypes) {
        fileMetrics.deleteAll(fileMetrics.findByGeneration_Id(generation.getId()));
        if (fileTypes == null || fileTypes.isEmpty()) { fileMetrics.flush(); return; }
        List<AiGenerationFileMetricEntity> metrics = new ArrayList<>();
        for (FileTypeMetric f : fileTypes) {
            FileCategory category = parseFileCategory(f.type());
            metrics.add(new AiGenerationFileMetricEntity(generation, f.extension(), category,
                    nz(f.linesAdded()), nz(f.linesDeleted()), nzi(f.filesCreated()), nzi(f.filesModified())));
        }
        fileMetrics.saveAll(metrics);
        fileMetrics.flush();
    }

    /** 按稳定轮次 ID 精确关联，禁止跨同一会话中的不同轮次串绑。 */
    private void linkSkillUsageEvents(Long userId, String generationId) {
        if (generationId == null || generationId.isBlank()) return;
        skillUsageEvents.linkGenerationByExternalId(userId, generationId);
    }

    private long nz(Long v) { return v == null ? 0L : v; }
    private int nzi(Integer v) { return v == null ? 0 : v; }

    private GenerationStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return GenerationStatus.COMPLETED;
        try { return GenerationStatus.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { throw error("INVALID_GENERATION_STATUS", HttpStatus.BAD_REQUEST); }
    }
    private TokenSource parseTokenSource(String raw) {
        if (raw == null || raw.isBlank()) return TokenSource.UNKNOWN;
        try { return TokenSource.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { return TokenSource.UNKNOWN; }
    }
    private TokenQuality parseTokenQuality(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return TokenQuality.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { throw error("INVALID_TOKEN_QUALITY", HttpStatus.BAD_REQUEST); }
    }
    private FileCategory parseFileCategory(String raw) {
        if (raw == null || raw.isBlank()) return FileCategory.OTHER;
        try { return FileCategory.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException ex) { return FileCategory.OTHER; }
    }
    private BusinessException error(String code, HttpStatus status) { return new BusinessException(code, code, status); }

    public record UpsertCommand(String generationId, String clientSessionId, String clientInstallationId,
            Instant startedAt, Instant endedAt, Long durationMs,
            String projectKey, String projectName, String projectSource,
            List<String> skillKeys, TokenUsage usage, CodeMetric code, List<FileTypeMetric> fileTypes,
            Integer toolCallCount, Integer toolFailureCount, String status,
            Long revision, String captureQuality, String modelId, String modelName,
            List<SkillInvocationMetric> skillInvocations, boolean observedLog, boolean observedStructured,
            boolean tokenMismatch, String codeSource, Long ingestLagMs, String durationSource) {
        public UpsertCommand(String generationId, String clientSessionId, String clientInstallationId,
                Instant startedAt, Instant endedAt, Long durationMs, String projectKey, String projectName,
                String projectSource, List<String> skillKeys, TokenUsage usage, CodeMetric code,
                List<FileTypeMetric> fileTypes, Integer toolCallCount, Integer toolFailureCount, String status,
                Long revision, String captureQuality, String modelId, String modelName,
                List<SkillInvocationMetric> skillInvocations) {
            this(generationId, clientSessionId, clientInstallationId, startedAt, endedAt, durationMs,
                    projectKey, projectName, projectSource, skillKeys, usage, code, fileTypes,
                    toolCallCount, toolFailureCount, status, revision, captureQuality, modelId, modelName,
                    skillInvocations, false, false, false, null, null, null);
        }
        public UpsertCommand(String generationId, String clientSessionId, String clientInstallationId,
                Instant startedAt, Instant endedAt, Long durationMs, String projectKey, String projectName,
                String projectSource, List<String> skillKeys, TokenUsage usage, CodeMetric code,
                List<FileTypeMetric> fileTypes, Integer toolCallCount, Integer toolFailureCount, String status) {
            this(generationId, clientSessionId, clientInstallationId, startedAt, endedAt, durationMs,
                    projectKey, projectName, projectSource, skillKeys, usage, code, fileTypes,
                    toolCallCount, toolFailureCount, status, null, null, null, null, null,
                    false, false, false, null, null, null);
        }
    }

    public record SkillInvocationMetric(String skillKey, Long skillVersionId, Instant invokedAt) {}

    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens,
            Long cacheReadTokens, Long cacheWriteTokens, Long cacheMissTokens,
            Long thinkingTokens, Integer modelCallCount, Long lastTokens, String source, String quality) {}

    public record CodeMetric(Long linesAdded, Long linesDeleted, Integer filesCreated, Integer filesModified) {}

    public record FileTypeMetric(String type, String extension, Long linesAdded, Long linesDeleted,
            Integer filesCreated, Integer filesModified) {}

    public record GenerationView(Long id, String generationId, String status, String primaryStage) {}
}
