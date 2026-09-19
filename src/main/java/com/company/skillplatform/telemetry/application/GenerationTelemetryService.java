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
import java.util.LinkedHashSet;
import java.util.List;
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
                nzi(command.toolCallCount()), nzi(command.toolFailureCount()));
        generation = generations.saveAndFlush(generation);

        replaceSkillRelations(generation, command.skillKeys());
        replaceFileMetrics(generation, command.fileTypes());
        linkSkillUsageEvents(userId, command.clientSessionId(), generation.getId());

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

    private void replaceSkillRelations(AiGenerationEntity generation, List<String> skillKeys) {
        generationSkills.deleteAll(generationSkills.findByGeneration_Id(generation.getId()));
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

    /** 把同一会话内已上报的 Skill 使用事件挂到本次 Generation（语义：Generation 1-N Skill Event）。 */
    private void linkSkillUsageEvents(Long userId, String clientSessionId, Long generationId) {
        if (clientSessionId == null || clientSessionId.isBlank()) return;
        skillUsageEvents.linkSessionToGeneration(userId, clientSessionId, generationId);
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
            Integer toolCallCount, Integer toolFailureCount, String status) {}

    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens,
            Long cacheReadTokens, Long cacheWriteTokens, Long cacheMissTokens,
            Long thinkingTokens, Integer modelCallCount, Long lastTokens, String source, String quality) {}

    public record CodeMetric(Long linesAdded, Long linesDeleted, Integer filesCreated, Integer filesModified) {}

    public record FileTypeMetric(String type, String extension, Long linesAdded, Long linesDeleted,
            Integer filesCreated, Integer filesModified) {}

    public record GenerationView(Long id, String generationId, String status, String primaryStage) {}
}
