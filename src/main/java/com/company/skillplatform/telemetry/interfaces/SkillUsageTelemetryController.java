package com.company.skillplatform.telemetry.interfaces;

import com.company.skillplatform.telemetry.application.SkillUsageTelemetryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.company.skillplatform.telemetry.application.SkillUsageConversationService;

@RestController
@RequestMapping("/api/v1/telemetry")
public class SkillUsageTelemetryController {
    private final SkillUsageTelemetryService service;
    private final SkillUsageConversationService conversations;
    public SkillUsageTelemetryController(SkillUsageTelemetryService service, SkillUsageConversationService conversations) { this.service = service; this.conversations = conversations; }

    @PostMapping("/skill-usage-events")
    SkillUsageTelemetryService.EventView create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return service.record((Long) authentication.getPrincipal(), new SkillUsageTelemetryService.CreateCommand(
                request.eventId, request.skillKey, request.skillVersionId, request.installationId,
                request.localDirectory, request.clientSessionId, request.generationId, request.client,
                request.clientVersion, request.agentType, request.model, request.invokedAt));
    }

    @PutMapping(value = "/skill-usage-events/{eventId}/conversation", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void conversation(@PathVariable String eventId, @RequestBody byte[] compressed, Authentication authentication) {
        conversations.stage(eventId, (Long) authentication.getPrincipal(), compressed);
    }

    public static class CreateRequest {
        @NotBlank @Size(max = 64) public String eventId;
        @NotBlank @Size(max = 128) public String skillKey;
        public Long skillVersionId;
        @Size(max = 64) public String installationId;
        @NotBlank @Size(max = 2048) public String localDirectory;
        @NotBlank @Size(max = 256) public String clientSessionId;
        @Size(max = 256) public String generationId;
        @NotBlank @Size(max = 64) public String client;
        @Size(max = 64) public String clientVersion;
        @Size(max = 64) public String agentType;
        @Size(max = 256) public String model;
        public Instant invokedAt;
    }
}
