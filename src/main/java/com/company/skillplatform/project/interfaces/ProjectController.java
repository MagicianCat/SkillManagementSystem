package com.company.skillplatform.project.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.project.application.ProjectControlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectControlService service;
    public ProjectController(ProjectControlService service) { this.service = service; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ProjectControlService.ProjectView create(@Valid @RequestBody CreateProject request, Authentication auth, HttpServletRequest http) { return service.create(new ProjectControlService.CreateProject(request.name(), request.description()), actor(auth), http.getRequestId()); }
    @GetMapping public PageResponse<ProjectControlService.ProjectView> list(Pageable pageable, Authentication auth) { return service.list(actor(auth), pageable); }
    @GetMapping("/{projectKey}") public ProjectControlService.ProjectView get(@PathVariable String projectKey, Authentication auth) { return service.get(projectKey, actor(auth)); }
    @PatchMapping("/{projectKey}") public ProjectControlService.ProjectView update(@PathVariable String projectKey, @Valid @RequestBody UpdateProject request, Authentication auth, HttpServletRequest http) { return service.update(projectKey, new ProjectControlService.UpdateProject(request.name(), request.description(), request.versionNo()), actor(auth), http.getRequestId()); }
    @PostMapping("/{projectKey}:archive") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(@PathVariable String projectKey, Authentication auth, HttpServletRequest http) { service.archive(projectKey, actor(auth), http.getRequestId()); }

    @GetMapping("/{projectKey}/members") public List<ProjectControlService.MemberView> members(@PathVariable String projectKey, Authentication auth) { return service.members(projectKey, actor(auth)); }
    @PutMapping("/{projectKey}/members/{userId}") public ProjectControlService.MemberView member(@PathVariable String projectKey, @PathVariable Long userId, @Valid @RequestBody MemberRequest request, Authentication auth, HttpServletRequest http) { return service.upsertMember(projectKey, userId, request.role(), actor(auth), http.getRequestId()); }
    @DeleteMapping("/{projectKey}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void removeMember(@PathVariable String projectKey, @PathVariable Long userId, Authentication auth, HttpServletRequest http) { service.removeMember(projectKey, userId, actor(auth), http.getRequestId()); }
    @PostMapping("/{projectKey}/members/{userId}:transfer-ownership") public ProjectControlService.MemberView transferOwnership(@PathVariable String projectKey, @PathVariable Long userId, Authentication auth, HttpServletRequest http) { return service.transferOwnership(projectKey, userId, actor(auth), http.getRequestId()); }

    @GetMapping("/{projectKey}/documents") public PageResponse<ProjectControlService.DocumentView> documents(@PathVariable String projectKey, Pageable pageable, Authentication auth) { return service.listDocuments(projectKey, actor(auth), pageable); }
    @PostMapping("/{projectKey}/documents") @ResponseStatus(HttpStatus.CREATED) public ProjectControlService.DocumentView createDocument(@PathVariable String projectKey, @Valid @RequestBody CreateDocument request, Authentication auth, HttpServletRequest http) { return service.createDocument(projectKey, new ProjectControlService.CreateDocument(request.documentType(), request.title(), request.markdownContent(), request.skillSnapshots(), request.assumptions(), request.openQuestions(), request.sourceDocumentIds()), actor(auth), http.getRequestId()); }
    @GetMapping("/{projectKey}/documents/{documentId}") public ProjectControlService.DocumentView document(@PathVariable String projectKey, @PathVariable Long documentId, Authentication auth) { return service.getDocument(projectKey, documentId, actor(auth)); }
    @PutMapping("/{projectKey}/documents/{documentId}/draft") public ProjectControlService.DocumentView draft(@PathVariable String projectKey, @PathVariable Long documentId, @Valid @RequestBody SaveDraft request, Authentication auth, HttpServletRequest http) { return service.saveDraft(projectKey, documentId, new ProjectControlService.SaveDraft(request.title(), request.markdownContent(), request.versionNo(), request.sourceType(), request.profileKey(), request.agentSessionId(), request.agentJobId(), request.skillSnapshots(), request.assumptions(), request.openQuestions(), request.sourceDocumentIds()), actor(auth), http.getRequestId()); }
    @GetMapping("/{projectKey}/documents/{documentId}/revisions") public List<ProjectControlService.RevisionView> revisions(@PathVariable String projectKey, @PathVariable Long documentId, Authentication auth) { return service.revisions(projectKey, documentId, actor(auth)); }
    @PostMapping("/{projectKey}/documents/{documentId}:publish") public ProjectControlService.DocumentView publish(@PathVariable String projectKey, @PathVariable Long documentId, @Valid @RequestBody PublishRequest request, Authentication auth, HttpServletRequest http) { return service.publish(projectKey, documentId, new ProjectControlService.PublishCommand(request.revisionId(), request.versionNo()), actor(auth), http.getRequestId()); }

    private Long actor(Authentication auth) { return (Long) auth.getPrincipal(); }
    public record CreateProject(@NotBlank @Size(max = 255) String name, @Size(max = 2000) String description) {}
    public record UpdateProject(@NotBlank @Size(max = 255) String name, @Size(max = 2000) String description, int versionNo) {}
    public record MemberRequest(@NotBlank String role) {}
    public record CreateDocument(@NotBlank String documentType, @NotBlank @Size(max = 255) String title, @NotBlank String markdownContent, Object skillSnapshots, Object assumptions, Object openQuestions, List<Long> sourceDocumentIds) {}
    public record SaveDraft(@Size(max = 255) String title, @NotBlank String markdownContent, int versionNo, String sourceType, String profileKey, String agentSessionId, String agentJobId, Object skillSnapshots, Object assumptions, Object openQuestions, List<Long> sourceDocumentIds) {}
    public record PublishRequest(@NotNull Long revisionId, int versionNo) {}
}
