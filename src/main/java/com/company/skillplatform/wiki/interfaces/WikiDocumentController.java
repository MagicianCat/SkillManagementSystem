package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/wiki")
public class WikiDocumentController {
    private final WikiDocumentService service;
    public WikiDocumentController(WikiDocumentService service) { this.service = service; }
    @GetMapping("/teams") public List<WikiDocumentService.TeamView> teams(Authentication a) { return service.visibleTeams((Long) a.getPrincipal()); }
    @GetMapping("/teams/search") public PageResponse<WikiDocumentService.TeamView> searchTeams(@RequestParam(required=false) String keyword, Pageable pageable, Authentication a) { return service.searchTeams((Long) a.getPrincipal(), keyword, pageable); }
    @GetMapping("/documents") public PageResponse<WikiDocumentService.DocumentView> search(@RequestParam(required=false) Long teamId, @RequestParam(required=false) String skillKey, @RequestParam(required=false) String documentType, @RequestParam(required=false) String keyword, Pageable pageable, Authentication a) { return service.search((Long) a.getPrincipal(), teamId, skillKey, documentType, keyword, pageable); }
    @GetMapping("/documents/{id}") public WikiDocumentService.DocumentView get(@PathVariable Long id, Authentication a) { return service.get(id, (Long) a.getPrincipal()); }
    @GetMapping("/documents/{id}/revisions") public List<WikiDocumentService.RevisionView> revisions(@PathVariable Long id, Authentication a) { return service.revisions(id, (Long) a.getPrincipal()); }
    @PostMapping("/documents") @ResponseStatus(HttpStatus.CREATED) public WikiDocumentService.DocumentView create(@Valid @RequestBody CreateRequest r, Authentication a, HttpServletRequest h) { return service.create(new WikiDocumentService.CreateCommand(r.title, r.documentType, r.teamId, r.skillIds, r.markdownContent), (Long) a.getPrincipal(), h.getRequestId()); }
    @PutMapping("/documents/{id}") public WikiDocumentService.DocumentView update(@PathVariable Long id, @Valid @RequestBody UpdateRequest r, Authentication a, HttpServletRequest h) { return service.update(id, new WikiDocumentService.UpdateCommand(r.title, r.markdownContent, r.versionNo), (Long) a.getPrincipal(), h.getRequestId()); }
    @PostMapping("/documents/{id}:restore") public WikiDocumentService.DocumentView restore(@PathVariable Long id, @Valid @RequestBody RestoreRequest r, Authentication a, HttpServletRequest h) { return service.restore(id, r.revisionId, r.versionNo, (Long) a.getPrincipal(), h.getRequestId()); }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(@PathVariable Long id, Authentication a, HttpServletRequest h) { service.archive(id, (Long) a.getPrincipal(), h.getRequestId()); }
    public record CreateRequest(@NotBlank @Size(max=255) String title, @NotBlank String documentType, Long teamId, @NotEmpty List<@NotNull Long> skillIds, @NotBlank String markdownContent) {}
    public record UpdateRequest(@NotBlank @Size(max=255) String title, @NotBlank String markdownContent, int versionNo) {}
    public record RestoreRequest(@NotNull Long revisionId, int versionNo) {}
}
