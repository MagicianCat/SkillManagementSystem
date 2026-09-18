package com.company.skillplatform.agentworkflow.interfaces;

import com.company.skillplatform.agentworkflow.application.AgentWorkflowService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/workflow-runs")
public class AgentWorkflowInternalController {
    private final AgentWorkflowService service;
    private final String serviceToken;

    public AgentWorkflowInternalController(AgentWorkflowService service,
            @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String serviceToken) {
        this.service = service;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/{runId}/events")
    public void event(@PathVariable Long runId,
            @RequestHeader(value = "X-SMS-Service-Token", required = false) String token,
            @RequestBody RuntimeEvent request) {
        if (token == null || !token.equals(serviceToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "SMS service authentication failed");
        }
        service.ingestEventInternal(runId, request.eventId(), request.nodeKey(), request.executionStatus(),
                request.resultCode(), request.resultJson());
    }

    public record RuntimeEvent(String eventId, String nodeKey, String executionStatus,
                               String resultCode, String resultJson) {}
}
