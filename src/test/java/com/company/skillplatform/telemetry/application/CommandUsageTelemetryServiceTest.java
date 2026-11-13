package com.company.skillplatform.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.company.skillplatform.telemetry.infrastructure.entity.CommandUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.CommandUsageEventRepository;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CommandUsageTelemetryServiceTest {
    private final CommandUsageEventRepository events = mock(CommandUsageEventRepository.class);
    private final IamUserRepository users = mock(IamUserRepository.class);
    private final AsdmCommandCategoryResolver categories = mock(AsdmCommandCategoryResolver.class);
    private final CommandUsageTelemetryService service = new CommandUsageTelemetryService(events, users, categories);
    private final String eventId = "11111111-2222-4333-8444-555555555555";

    private CommandUsageTelemetryService.CreateCommand command(String name) {
        return new CommandUsageTelemetryService.CreateCommand(eventId, name, null, null, "code-dev", null, "MENU",
                "STRUCTURED_COMMAND", null, "install-1", "session-1", "log-request-1", null,
                "CodeBuddyIDE", null, Instant.parse("2026-11-02T02:33:17Z"));
    }

    @Test
    void createsAndLinksEventThenAcceptsIdenticalRetry() {
        IamUserEntity user = mock(IamUserEntity.class);
        when(user.getId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(categories.resolve(null, "code-dev")).thenReturn(new AsdmCommandCategoryResolver.Resolution(null, null, null, null, "NO_ASSET"));
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.record(7L, command("code-review"));
        assertThat(result.commandName()).isEqualTo("code-review");
        verify(events).linkGenerationByExternalId(7L, "log-request-1");

        CommandUsageEventEntity saved = new CommandUsageEventEntity(eventId, user, "code-review", "code-dev",
                "MENU", "STRUCTURED_COMMAND", null, "install-1", "session-1", "log-request-1",
                null, "CodeBuddyIDE", null, Instant.parse("2026-11-02T02:33:17Z"), Instant.now());
        when(events.findByEventUuid(eventId)).thenReturn(Optional.of(saved));
        service.record(7L, command("code-review"));
        verify(events, times(1)).saveAndFlush(any());
        assertThatThrownBy(() -> service.record(7L, command("other-command")))
                .hasMessageContaining("EVENT_ID_CONFLICT");
    }

    @Test
    void enrichesExistingEventWithReqIdAndRejectsConflictingAttribution() {
        IamUserEntity user = mock(IamUserEntity.class);
        when(user.getId()).thenReturn(7L);
        CommandUsageEventEntity saved = new CommandUsageEventEntity(eventId, user, "req-workflow", "req-analyzer",
                "MENU", "STRUCTURED_COMMAND", null, "install-1", "session-1", "log-request-1",
                null, "CodeBuddyIDE", null, Instant.parse("2026-11-02T02:33:17Z"), Instant.now());
        when(events.findByEventUuid(eventId)).thenReturn(Optional.of(saved));
        var base = command("req-workflow");
        var enriched = new CommandUsageTelemetryService.CreateCommand(base.eventId(), base.commandName(),
                "REQ-001-secondshelf-mvp", "REQUEST_FILE_TREE", base.toolsetName(), base.asdmAssetId(), base.triggerType(),
                base.evidenceType(), base.commandVersion(), base.installationId(), base.clientSessionId(),
                base.generationId(), base.projectKey(), base.client(), base.clientVersion(), base.invokedAt());
        assertThat(service.record(7L, enriched).reqId()).isEqualTo("REQ-001-secondshelf-mvp");
        verify(events).saveAndFlush(saved);
        var conflict = new CommandUsageTelemetryService.CreateCommand(base.eventId(), base.commandName(),
                "REQ-002-other", "ARGUMENT", base.toolsetName(), base.asdmAssetId(), base.triggerType(), base.evidenceType(),
                base.commandVersion(), base.installationId(), base.clientSessionId(), base.generationId(),
                base.projectKey(), base.client(), base.clientVersion(), base.invokedAt());
        assertThatThrownBy(() -> service.record(7L, conflict)).hasMessageContaining("REQ_ID_CONFLICT");
    }

    @Test
    void freezesExplicitOtherStageAndRejectsConflictingAssetOnRetry() {
        IamUserEntity user = mock(IamUserEntity.class);
        when(user.getId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(categories.resolve(123L, "code-dev")).thenReturn(
                new AsdmCommandCategoryResolver.Resolution(123L, 55L, "other", "OTHER", "MATCHED"));
        when(events.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var base = command("code-review");
        var attributed = new CommandUsageTelemetryService.CreateCommand(base.eventId(), base.commandName(),
                base.reqId(), base.reqIdSource(), base.toolsetName(), 123L, base.triggerType(),
                base.evidenceType(), base.commandVersion(), base.installationId(), base.clientSessionId(),
                base.generationId(), base.projectKey(), base.client(), base.clientVersion(), base.invokedAt());
        service.record(7L, attributed);
        var capture = org.mockito.ArgumentCaptor.forClass(CommandUsageEventEntity.class);
        verify(events).saveAndFlush(capture.capture());
        var saved = capture.getValue();
        assertThat(saved.getAsdmAssetId()).isEqualTo(123L);
        assertThat(saved.getAsdmDevelopmentStage()).isEqualTo("OTHER");
        assertThat(saved.getAsdmMatchStatus()).isEqualTo("MATCHED");
        when(events.findByEventUuid(eventId)).thenReturn(Optional.of(saved));
        service.record(7L, attributed);
        verify(categories, times(1)).resolve(123L, "code-dev");
        var conflict = new CommandUsageTelemetryService.CreateCommand(base.eventId(), base.commandName(),
                base.reqId(), base.reqIdSource(), base.toolsetName(), 456L, base.triggerType(),
                base.evidenceType(), base.commandVersion(), base.installationId(), base.clientSessionId(),
                base.generationId(), base.projectKey(), base.client(), base.clientVersion(), base.invokedAt());
        assertThatThrownBy(() -> service.record(7L, conflict)).hasMessageContaining("ASDM_ASSET_ID_CONFLICT");
    }
}
