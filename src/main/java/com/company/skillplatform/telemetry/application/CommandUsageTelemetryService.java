package com.company.skillplatform.telemetry.application;

import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.telemetry.infrastructure.entity.CommandUsageEventEntity;
import com.company.skillplatform.telemetry.infrastructure.repository.CommandUsageEventRepository;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandUsageTelemetryService {
    private final CommandUsageEventRepository events;
    private final IamUserRepository users;

    public CommandUsageTelemetryService(CommandUsageEventRepository events, IamUserRepository users) {
        this.events = events; this.users = users;
    }

    @Transactional
    public EventView record(Long userId, CreateCommand command) {
        try { UUID.fromString(command.eventId()); }
        catch (Exception ex) { throw error("EVENT_ID_INVALID", HttpStatus.BAD_REQUEST); }
        return events.findByEventUuid(command.eventId()).map(existing -> {
            if (!existing.sameIdentity(userId, command.commandName(), command.clientSessionId(), command.generationId()))
                throw error("EVENT_ID_CONFLICT", HttpStatus.CONFLICT);
            events.linkGenerationByExternalId(userId, command.generationId());
            return view(existing);
        }).orElseGet(() -> {
            if (!"MENU".equals(command.triggerType()) && !"TYPED".equals(command.triggerType()))
                throw error("TRIGGER_TYPE_INVALID", HttpStatus.BAD_REQUEST);
            if (!"STRUCTURED_COMMAND".equals(command.evidenceType()) && !"ACTION_FILE_READ".equals(command.evidenceType()))
                throw error("EVIDENCE_TYPE_INVALID", HttpStatus.BAD_REQUEST);
            var user = users.findById(userId).orElseThrow(() -> error("USER_NOT_FOUND", HttpStatus.UNAUTHORIZED));
            Instant now = Instant.now();
            CommandUsageEventEntity event = events.saveAndFlush(new CommandUsageEventEntity(command.eventId(), user,
                    command.commandName(), command.toolsetName(), command.triggerType(), command.evidenceType(),
                    command.commandVersion(), command.installationId(), command.clientSessionId(), command.generationId(),
                    command.projectKey(), command.client(), command.clientVersion(), command.invokedAt(), now));
            events.linkGenerationByExternalId(userId, command.generationId());
            return view(event);
        });
    }

    private EventView view(CommandUsageEventEntity event) {
        return new EventView(event.getEventUuid(), event.getCommandName(), event.getInvokedAt());
    }
    private BusinessException error(String code, HttpStatus status) { return new BusinessException(code, code, status); }

    public record CreateCommand(String eventId, String commandName, String toolsetName, String triggerType,
            String evidenceType, String commandVersion, String installationId, String clientSessionId,
            String generationId, String projectKey, String client, String clientVersion, Instant invokedAt) {}
    public record EventView(String eventId, String commandName, Instant invokedAt) {}
}
