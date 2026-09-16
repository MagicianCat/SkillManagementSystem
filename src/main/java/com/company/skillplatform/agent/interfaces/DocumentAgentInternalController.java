package com.company.skillplatform.agent.interfaces;

import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.project.application.ProjectControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/document-agent")
public class DocumentAgentInternalController {
    private final AgentRunService runs;
    private final ProjectControlService projects;
    private final String serviceToken;
    public DocumentAgentInternalController(AgentRunService runs, ProjectControlService projects,
                                           @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String serviceToken) {
        this.runs = runs; this.projects = projects; this.serviceToken = serviceToken;
    }
    @PostMapping("/runs")
    public TokenResponse issue(@RequestHeader(value = "X-SMS-Service-Token", required = false) String token,
                               @Valid @RequestBody IssueRequest request) {
        if (token == null || !token.equals(serviceToken)) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "SMS service authentication failed");
        projects.assertAgentAccess(request.projectKey(), request.actorId());
        AgentRunService.IssuedRun issued = runs.issueDocumentRun(request.actorId(), request.projectKey(), request.documentId(), request.profileKey());
        return new TokenResponse(issued.run().runRef(), issued.token(), issued.run().expiresAt());
    }
    public record IssueRequest(@NotNull Long actorId, @NotBlank String projectKey, Long documentId, @NotBlank String profileKey) {}
    public record TokenResponse(String runRef, String token, java.time.Instant expiresAt) {}
}
