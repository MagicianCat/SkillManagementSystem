package com.company.skillplatform.project.interfaces;

import com.company.skillplatform.project.application.DocumentAgentGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectKey}/document-agent")
public class DocumentAgentController {
    private final DocumentAgentGatewayService service;
    public DocumentAgentController(DocumentAgentGatewayService service) { this.service = service; }
    @PostMapping("/sessions") public Map<String,Object> create(@PathVariable String projectKey, @Valid @RequestBody CreateSession request, Authentication auth, HttpServletRequest http) { return service.createSession(projectKey, request.documentId(), request.profileKey(), actor(auth), key(http)); }
    @GetMapping("/sessions/{sessionId}") public Map<String,Object> session(@PathVariable String projectKey, @PathVariable String sessionId, Authentication auth) { return service.getSession(projectKey, sessionId, actor(auth)); }
    @DeleteMapping("/sessions/{sessionId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void close(@PathVariable String projectKey, @PathVariable String sessionId, Authentication auth) { service.closeSession(projectKey, sessionId, actor(auth)); }
    @PostMapping("/sessions/{sessionId}/turns") public Map<String,Object> turn(@PathVariable String projectKey, @PathVariable String sessionId, @Valid @RequestBody Turn request, Authentication auth, HttpServletRequest http) { return service.sendTurn(projectKey, sessionId, request.content(), key(http), actor(auth)); }
    @GetMapping("/jobs/{jobId}") public Map<String,Object> job(@PathVariable String projectKey, @PathVariable String jobId, Authentication auth) { return service.getJob(projectKey, jobId, actor(auth)); }
    @GetMapping(value = "/jobs/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE) public ResponseEntity<String> events(@PathVariable String projectKey, @PathVariable String jobId, @RequestParam(required = false, defaultValue = "0") Long after, Authentication auth) { return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(service.getEvents(projectKey, jobId, actor(auth), after)); }
    private Long actor(Authentication auth) { return (Long) auth.getPrincipal(); }
    private String key(HttpServletRequest request) { String value = request.getHeader("Idempotency-Key"); return value == null || value.isBlank() ? request.getRequestId() : value; }
    public record CreateSession(Long documentId, @NotBlank String profileKey) {}
    public record Turn(@NotBlank @Size(max = 10000) String content) {}
}
