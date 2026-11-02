package com.company.skillplatform.telemetry.interfaces;

import com.company.skillplatform.telemetry.application.CommandUsageTelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/telemetry")
public class CommandUsageTelemetryController {
    private final CommandUsageTelemetryService service;
    public CommandUsageTelemetryController(CommandUsageTelemetryService service) { this.service = service; }

    @PostMapping("/command-usage-events")
    CommandUsageTelemetryService.EventView create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return service.record((Long) authentication.getPrincipal(), new CommandUsageTelemetryService.CreateCommand(
                request.eventId, request.commandName, request.reqId, request.reqIdSource,
                request.toolsetName, request.triggerType,
                request.evidenceType, request.commandVersion, request.installationId, request.clientSessionId,
                request.generationId, request.projectKey, request.client, request.clientVersion, request.invokedAt));
    }

    public static class CreateRequest {
        @NotBlank @Size(max = 64) public String eventId;
        @NotBlank @Size(max = 128) public String commandName;
        @Size(max = 256) public String reqId;
        @Size(max = 32) public String reqIdSource;
        @Size(max = 128) public String toolsetName;
        @NotBlank @Size(max = 32) public String triggerType;
        @NotBlank @Size(max = 32) public String evidenceType;
        @Size(max = 64) public String commandVersion;
        @Size(max = 64) public String installationId;
        @NotBlank @Size(max = 256) public String clientSessionId;
        @NotBlank @Size(max = 256) public String generationId;
        @Size(max = 128) public String projectKey;
        @NotBlank @Size(max = 64) public String client;
        @Size(max = 64) public String clientVersion;
        @NotNull public Instant invokedAt;
    }
}
