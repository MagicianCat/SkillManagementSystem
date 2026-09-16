package com.company.skillplatform.project.application;

import com.company.skillplatform.agent.application.AgentRunService;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.project.infrastructure.entity.ProjectDocumentEntity;
import com.company.skillplatform.project.infrastructure.repository.ProjectDocumentRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class DocumentAgentGatewayService {
    private final ProjectControlService projects;
    private final ProjectDocumentRepository documents;
    private final AgentRunService runs;
    private final RestClient client;
    private final String serviceToken;

    public DocumentAgentGatewayService(ProjectControlService projects, ProjectDocumentRepository documents, AgentRunService runs,
                                       RestClient.Builder builder,
                                       @Value("${skill-platform.document-agent.gateway-url:http://127.0.0.1:8090}") String gatewayUrl,
                                       @Value("${skill-platform.internal-service-token:${SMS_SERVICE_TOKEN:local-sms-service-token}}") String serviceToken) {
        this.projects = projects; this.documents = documents; this.runs = runs; this.client = builder.baseUrl(gatewayUrl).build(); this.serviceToken = serviceToken;
    }

    public Map<String, Object> createSession(String projectKey, Long documentId, String profileKey, Long actorId, String idempotencyKey) {
        projects.assertAgentAccess(projectKey, actorId);
        if (documentId != null) documents.findByIdAndProjectId(documentId, projectId(projectKey, actorId)).orElseThrow(() -> error("PROJECT_DOCUMENT_NOT_FOUND", "Project document not found", HttpStatus.NOT_FOUND));
        AgentRunService.IssuedRun issued = runs.issueDocumentRun(actorId, projectKey, documentId, profileKey);
        return exchange(() -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("actor_id", String.valueOf(actorId)); body.put("project_id", projectKey); body.put("profile_key", profileKey);
            body.put("document_id", documentId == null ? null : String.valueOf(documentId)); body.put("mcp_token", issued.token()); body.put("idempotency_key", idempotencyKey);
            return client.post().uri("/internal/v1/document-sessions").header("X-SMS-Service-Token", serviceToken).body(body).retrieve().body(new ParameterizedTypeReference<>() {});
        });
    }

    public Map<String, Object> getSession(String projectKey, String sessionId, Long actorId) { projects.assertAgentAccess(projectKey, actorId); return exchange(() -> client.get().uri("/internal/v1/document-sessions/{id}", sessionId).header("X-SMS-Service-Token", serviceToken).retrieve().body(new ParameterizedTypeReference<>() {})); }
    public Map<String, Object> sendTurn(String projectKey, String sessionId, String content, String idempotencyKey, Long actorId) { projects.assertAgentAccess(projectKey, actorId); return exchange(() -> client.post().uri("/internal/v1/document-sessions/{id}/turns", sessionId).header("X-SMS-Service-Token", serviceToken).body(Map.of("content", content, "idempotency_key", idempotencyKey)).retrieve().body(new ParameterizedTypeReference<>() {})); }
    public Map<String, Object> getJob(String projectKey, String jobId, Long actorId) { projects.assertAgentAccess(projectKey, actorId); return exchange(() -> client.get().uri("/internal/v1/document-jobs/{id}", jobId).header("X-SMS-Service-Token", serviceToken).retrieve().body(new ParameterizedTypeReference<>() {})); }
    public String getEvents(String projectKey, String jobId, Long actorId, Long after) { projects.assertAgentAccess(projectKey, actorId); return exchange(() -> client.get().uri(uri -> uri.path("/internal/v1/document-jobs/{id}/events").queryParam("last_event_id", after == null ? 0 : after).build(jobId)).header("X-SMS-Service-Token", serviceToken).accept(MediaType.TEXT_EVENT_STREAM).retrieve().body(String.class)); }
    public void closeSession(String projectKey, String sessionId, Long actorId) { projects.assertAgentAccess(projectKey, actorId); exchange(() -> { client.delete().uri("/internal/v1/document-sessions/{id}", sessionId).header("X-SMS-Service-Token", serviceToken).retrieve().toBodilessEntity(); return Map.of(); }); }

    private Long projectId(String key, Long actorId) { return projects.projectIdForAgent(key, actorId); }
    private <T> T exchange(java.util.function.Supplier<T> call) { try { return call.get(); } catch (RestClientResponseException e) { throw error("DOCUMENT_AGENT_GATEWAY_ERROR", "Document Agent Gateway request failed", HttpStatus.BAD_GATEWAY); } catch (RuntimeException e) { throw error("DOCUMENT_AGENT_GATEWAY_UNAVAILABLE", "Document Agent Gateway is unavailable", HttpStatus.BAD_GATEWAY); } }
    private BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
}
