package com.company.skillplatform.project.interfaces;

import com.company.skillplatform.project.application.DocumentAgentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Independent document-agent entry point. It deliberately has no DSH session parameter. */
@RestController
@RequestMapping("/api/v1/document-agent")
public class DocumentAgentSessionController {
    private final DocumentAgentSessionService service;
    public DocumentAgentSessionController(DocumentAgentSessionService service) { this.service = service; }

    @GetMapping("/sessions")
    public List<DocumentAgentSessionService.SessionView> list(@RequestParam(required = false) String projectKey,
                                                               @RequestParam(defaultValue = "20") int limit, Authentication auth) {
        return service.list(projectKey, actor(auth), limit);
    }

    @PostMapping("/sessions")
    public DocumentAgentSessionService.SessionView create(@Valid @RequestBody CreateSession request,
                                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                           HttpServletRequest http, Authentication auth) {
        return service.create(request.projectKey(), actor(auth), request.profileKey(), request.mode(), request.documentId(), request.title(), key(idempotencyKey, http));
    }

    @GetMapping("/sessions/{sessionKey}")
    public DocumentAgentSessionService.SessionView get(@PathVariable String sessionKey, Authentication auth) { return service.get(sessionKey, actor(auth)); }

    @DeleteMapping("/sessions/{sessionKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable String sessionKey, Authentication auth) { service.close(sessionKey, actor(auth)); }

    @PostMapping("/sessions/{sessionKey}/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentAgentSessionService.JobView turn(@PathVariable String sessionKey, @Valid @RequestBody Turn request,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    HttpServletRequest http, Authentication auth) {
        return service.turn(sessionKey, actor(auth), request.instruction(), key(idempotencyKey, http), request.sourceArtifactIds(), request.feishuDocuments());
    }

    @GetMapping("/jobs/{jobKey}")
    public DocumentAgentSessionService.JobView job(@PathVariable String jobKey, Authentication auth) { return service.getJob(jobKey, actor(auth)); }

    @PostMapping("/jobs/{jobKey}:cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String jobKey, Authentication auth) { service.cancelJob(jobKey, actor(auth)); }

    @GetMapping(value = "/jobs/{jobKey}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> events(@PathVariable String jobKey, @RequestParam(defaultValue = "0") long after, Authentication auth) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(service.events(jobKey, actor(auth), after));
    }

    @GetMapping("/feishu-documents:search")
    public Object searchFeishu(@RequestParam String query, @RequestParam(defaultValue = "10") int limit,
                               @RequestParam(defaultValue = "0") int offset, Authentication auth) {
        return service.searchFeishu(actor(auth), query, limit, offset);
    }

    @PostMapping("/feishu-documents:resolve")
    public Object resolveFeishu(@Valid @RequestBody ResolveFeishu request, Authentication auth) {
        return service.resolveFeishu(actor(auth), request.docId(), request.docType());
    }

    private Long actor(Authentication auth) { return (Long) auth.getPrincipal(); }
    private String key(String supplied, HttpServletRequest http) { return supplied == null || supplied.isBlank() ? http.getRequestId() : supplied; }
    public record CreateSession(@NotBlank String projectKey, @NotBlank String profileKey, String mode, Long documentId, @Size(max = 255) String title) {}
    public record Turn(@NotBlank @Size(max = 10000) String instruction, List<Long> sourceArtifactIds, List<DocumentAgentSessionService.FeishuContext> feishuDocuments) {}
    public record ResolveFeishu(@NotBlank String docId, @NotBlank String docType) {}
}
