package com.company.skillplatform.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.company.skillplatform.skill.domain.DevelopmentStage;
import com.company.skillplatform.skill.infrastructure.entity.SkillEntity;
import com.company.skillplatform.skill.infrastructure.repository.SkillRepository;
import com.company.skillplatform.telemetry.domain.GenerationStage;
import com.company.skillplatform.telemetry.domain.GenerationStatus;
import com.company.skillplatform.telemetry.infrastructure.entity.AiGenerationEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.AiGenerationFileMetricRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.AiGenerationRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.AiGenerationSkillRepository;
import com.company.skillplatform.telemetry.infrastructure.repository.SkillUsageEventRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GenerationTelemetryServiceTest {
    private final AiGenerationRepository generations = mock(AiGenerationRepository.class);
    private final AiGenerationSkillRepository generationSkills = mock(AiGenerationSkillRepository.class);
    private final AiGenerationFileMetricRepository fileMetrics = mock(AiGenerationFileMetricRepository.class);
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final SkillRepository skills = mock(SkillRepository.class);
    private final SkillUsageEventRepository skillUsageEvents = mock(SkillUsageEventRepository.class);
    private final GenerationTelemetryService service = new GenerationTelemetryService(
            generations, generationSkills, fileMetrics, users, skills, skillUsageEvents);

    private IamUserEntity user;

    @BeforeEach
    void setUp() {
        user = mock(IamUserEntity.class);
        when(user.getId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(generationSkills.findByGeneration_Id(any())).thenReturn(List.of());
        when(fileMetrics.findByGeneration_Id(any())).thenReturn(List.of());
        when(generations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private GenerationTelemetryService.UpsertCommand command(String generationId, List<String> skillKeys) {
        return new GenerationTelemetryService.UpsertCommand(
                generationId, "session-1", "install-1",
                Instant.parse("2026-09-18T10:00:00Z"), Instant.parse("2026-09-18T10:09:31Z"), 571000L,
                "proj-key", "sms-backend", "GIT_REMOTE", skillKeys,
                new GenerationTelemetryService.TokenUsage(585930L, 10155L, 596085L, 0L, 0L, 585930L, 4740L, 27, 38317L,
                        "CODEBUDDY_UPSTREAM_USAGE", "EXACT"),
                new GenerationTelemetryService.CodeMetric(418L, 72L, 5, 8),
                List.of(new GenerationTelemetryService.FileTypeMetric("JAVA", "java", 326L, 63L, 3, 5)),
                42, 2, "COMPLETED");
    }

    @Test
    void createsNewGenerationWithTokensAndMetrics() {
        when(generations.findByUser_IdAndHookGenerationId(7L, "g-1")).thenReturn(Optional.empty());
        SkillEntity skill = mock(SkillEntity.class);
        when(skill.getDevelopmentStage()).thenReturn(DevelopmentStage.BACKEND_CODING);
        when(skills.findBySkillKeyIn(anyCollection())).thenReturn(List.of(skill));

        var view = service.upsert(7L, command("g-1", List.of("java-backend")));

        assertThat(view.generationId()).isEqualTo("g-1");
        assertThat(view.status()).isEqualTo(GenerationStatus.COMPLETED.name());
        assertThat(view.primaryStage()).isEqualTo(GenerationStage.BACKEND_CODING.name());
        verify(generations).saveAndFlush(argThat(g ->
                g.getStatus() == GenerationStatus.COMPLETED
                        && g.getPrimaryStage() == GenerationStage.BACKEND_CODING));
        verify(skillUsageEvents).linkSessionToGeneration(eq(7L), eq("session-1"), any());
    }

    @Test
    void upsertIsIdempotentOnUserAndGenerationId() {
        AiGenerationEntity existing = new AiGenerationEntity(user, "session-1", "g-1", GenerationStatus.PARTIAL,
                Instant.parse("2026-09-18T10:00:00Z"));
        when(generations.findByUser_IdAndHookGenerationId(7L, "g-1")).thenReturn(Optional.of(existing));
        when(skills.findBySkillKeyIn(anyCollection())).thenReturn(List.of());

        service.upsert(7L, command("g-1", List.of()));

        // 不新建，只更新同一行
        verify(generations, times(1)).saveAndFlush(existing);
        assertThat(existing.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
    }

    @Test
    void derivesMultiStageWhenSkillsSpanStages() {
        when(generations.findByUser_IdAndHookGenerationId(7L, "g-2")).thenReturn(Optional.empty());
        SkillEntity backend = mock(SkillEntity.class);
        when(backend.getDevelopmentStage()).thenReturn(DevelopmentStage.BACKEND_CODING);
        SkillEntity testing = mock(SkillEntity.class);
        when(testing.getDevelopmentStage()).thenReturn(DevelopmentStage.TESTING);
        when(skills.findBySkillKeyIn(anyCollection())).thenReturn(List.of(backend, testing));

        var view = service.upsert(7L, command("g-2", List.of("java-backend", "unit-testing")));

        assertThat(view.primaryStage()).isEqualTo(GenerationStage.MULTI_STAGE.name());
    }

    @Test
    void rejectsMissingGenerationId() {
        assertThatThrownBy(() -> service.upsert(7L, command(null, List.of())))
                .hasMessageContaining("GENERATION_ID_REQUIRED");
        assertThatThrownBy(() -> service.upsert(7L, command("  ", List.of())))
                .hasMessageContaining("GENERATION_ID_REQUIRED");
    }

    @Test
    void rejectsUnknownUser() {
        when(users.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsert(99L, command("g-9", List.of())))
                .hasMessageContaining("USER_NOT_FOUND");
    }

    @Test
    void invalidTokenQualityFallsBackToNull_notThrow() {
        when(generations.findByUser_IdAndHookGenerationId(7L, "g-3")).thenReturn(Optional.empty());
        when(skills.findBySkillKeyIn(anyCollection())).thenReturn(List.of());
        var cmd = new GenerationTelemetryService.UpsertCommand(
                "g-3", "session-1", null, Instant.now(), null, null,
                null, null, null, List.of(),
                new GenerationTelemetryService.TokenUsage(1L, 1L, 2L, null, null, null, null, 1, null,
                        "UNKNOWN_SOURCE_XYZ", null),
                null, null, null, null, null);
        var view = service.upsert(7L, cmd);
        assertThat(view.generationId()).isEqualTo("g-3");
    }
}
