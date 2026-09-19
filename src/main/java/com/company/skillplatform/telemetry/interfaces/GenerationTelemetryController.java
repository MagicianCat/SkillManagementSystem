package com.company.skillplatform.telemetry.interfaces;

import com.company.skillplatform.telemetry.application.GenerationTelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generation 上报入口：Upsert 一次 Generation 最终统计（幂等键 user_id + generationId）。 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class GenerationTelemetryController {
    private final GenerationTelemetryService service;

    public GenerationTelemetryController(GenerationTelemetryService service) { this.service = service; }

    @PostMapping("/generations")
    GenerationTelemetryService.GenerationView upsert(@Valid @RequestBody UpsertRequest request, Authentication authentication) {
        return service.upsert((Long) authentication.getPrincipal(), new GenerationTelemetryService.UpsertCommand(
                request.generationId, request.sessionId, request.clientInstallationId,
                request.startedAt, request.endedAt, request.durationMs,
                request.projectKey, request.projectName, request.projectSource,
                request.skillKeys,
                request.usage == null ? null : new GenerationTelemetryService.TokenUsage(
                        request.usage.inputTokens, request.usage.outputTokens, request.usage.totalTokens,
                        request.usage.cacheReadTokens, request.usage.cacheWriteTokens, request.usage.cacheMissTokens,
                        request.usage.thinkingTokens, request.usage.modelCallCount, request.usage.lastTokens,
                        request.usage.source, request.usage.quality),
                request.code == null ? null : new GenerationTelemetryService.CodeMetric(
                        request.code.linesAdded, request.code.linesDeleted, request.code.filesCreated, request.code.filesModified),
                request.fileTypes == null ? null : request.fileTypes.stream().map(f -> new GenerationTelemetryService.FileTypeMetric(
                        f.type, f.extension, f.linesAdded, f.linesDeleted, f.filesCreated, f.filesModified)).toList(),
                request.toolCallCount, request.toolFailureCount, request.status));
    }

    public static class UpsertRequest {
        @NotBlank @Size(max = 256) public String generationId;
        @NotBlank @Size(max = 256) public String sessionId;
        @Size(max = 64) public String clientInstallationId;
        @NotNull public Instant startedAt;
        public Instant endedAt;
        public Long durationMs;
        @Size(max = 128) public String projectKey;
        @Size(max = 256) public String projectName;
        @Size(max = 32) public String projectSource;
        public List<@NotBlank @Size(max = 128) String> skillKeys;
        @Valid public Usage usage;
        @Valid public Code code;
        @Valid public List<FileType> fileTypes;
        public Integer toolCallCount;
        public Integer toolFailureCount;
        @Size(max = 32) public String status;
    }

    public static class Usage {
        public Long inputTokens; public Long outputTokens; public Long totalTokens;
        public Long cacheReadTokens; public Long cacheWriteTokens; public Long cacheMissTokens;
        public Long thinkingTokens; public Integer modelCallCount; public Long lastTokens;
        @Size(max = 32) public String source;
        @Size(max = 32) public String quality;
    }

    public static class Code {
        public Long linesAdded; public Long linesDeleted; public Integer filesCreated; public Integer filesModified;
    }

    public static class FileType {
        @Size(max = 32) public String type;
        @Size(max = 32) public String extension;
        public Long linesAdded; public Long linesDeleted; public Integer filesCreated; public Integer filesModified;
    }
}
