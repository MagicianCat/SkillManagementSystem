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
    private final CommandUsageTelemetryService service = new CommandUsageTelemetryService(events, users);
    private final String eventId = "11111111-2222-4333-8444-555555555555";

    private CommandUsageTelemetryService.CreateCommand command(String name) {
        return new CommandUsageTelemetryService.CreateCommand(eventId, name, "code-dev", "MENU",
                "STRUCTURED_COMMAND", null, "install-1", "session-1", "log-request-1", null,
                "CodeBuddyIDE", null, Instant.parse("2026-11-02T02:33:17Z"));
    }

    @Test
    void createsAndLinksEventThenAcceptsIdenticalRetry() {
        IamUserEntity user = mock(IamUserEntity.class);
        when(user.getId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
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
}
