package com.company.skillplatform.agent.interfaces;

import com.company.skillplatform.agent.application.AgentRunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/agent-runs")
public class AgentRunController {
    private final AgentRunService runs;
    public AgentRunController(AgentRunService runs) { this.runs = runs; }
    @PostMapping
    public AgentRunResponse create(Authentication auth, @Valid @RequestBody CreateRequest request) {
        Long userId = Long.valueOf(auth.getName());
        var issued = runs.issue(userId, request.profileKey(), request.platform(), request.osType());
        return new AgentRunResponse(issued.run().runRef(), issued.token(), issued.run().expiresAt(), "v1");
    }
    public record CreateRequest(@NotBlank String profileKey, String platform, String osType) {}
    public record AgentRunResponse(String runRef, String token, java.time.Instant expiresAt, String toolSchemaVersion) {}
}
