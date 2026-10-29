package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.wiki.application.WikiDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.LinkedHashSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
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
    @GetMapping("/documents/matches") public List<WikiDocumentService.DocumentMatchView> matches(@RequestParam Long teamId, @RequestParam String title, Authentication a) { return service.findTeamDocumentsByTitle((Long) a.getPrincipal(), teamId, title); }
    @GetMapping("/documents/{id}") public WikiDocumentService.DocumentView get(@PathVariable Long id, Authentication a) { return service.get(id, (Long) a.getPrincipal()); }
    @GetMapping("/documents/{id}/metadata") public DocumentMetadata metadata(@PathVariable Long id, Authentication a) {
        var wiki = service.get(id, (Long) a.getPrincipal());
        return new DocumentMetadata(wiki.id(), wiki.title());
    }
    @GetMapping("/documents/{id}/download") public ResponseEntity<ByteArrayResource> download(@PathVariable Long id, Authentication a) {
        var wiki = service.get(id, (Long) a.getPrincipal());
        byte[] content = wiki.markdownContent().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=wiki-" + id + ".md")
                .body(new ByteArrayResource(content));
    }
    @PostMapping("/documents:download") public ResponseEntity<ByteArrayResource> downloadSelected(@Valid @RequestBody DownloadRequest request, Authentication a) throws IOException {
        List<WikiDocumentService.DocumentView> selected = new java.util.ArrayList<>();
        for (Long id : new LinkedHashSet<>(request.documentIds())) selected.add(service.get(id, (Long) a.getPrincipal()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var wiki : selected) {
                String safeTitle = wiki.title().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                zip.putNextEntry(new ZipEntry("wiki-" + wiki.id() + "-" + safeTitle + ".md"));
                zip.write(wiki.markdownContent().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=wiki-documents.zip")
                .body(new ByteArrayResource(bytes.toByteArray()));
    }
    @GetMapping("/documents/{id}/revisions") public List<WikiDocumentService.RevisionView> revisions(@PathVariable Long id, Authentication a) { return service.revisions(id, (Long) a.getPrincipal()); }
    @PostMapping("/documents") @ResponseStatus(HttpStatus.CREATED) public WikiDocumentService.DocumentView create(@Valid @RequestBody CreateRequest r, Authentication a, HttpServletRequest h) { return service.create(new WikiDocumentService.CreateCommand(r.title, r.documentType, r.teamId, r.skillIds, r.markdownContent, r.parentId), (Long) a.getPrincipal(), h.getRequestId()); }
    @PutMapping("/documents/{id}") public WikiDocumentService.DocumentView update(@PathVariable Long id, @Valid @RequestBody UpdateRequest r, Authentication a, HttpServletRequest h) { return service.update(id, new WikiDocumentService.UpdateCommand(r.title, r.markdownContent, r.versionNo, r.skillIds, r.parentId, Boolean.TRUE.equals(r.parentSpecified)), (Long) a.getPrincipal(), h.getRequestId()); }
    @PostMapping("/documents/{id}:restore") public WikiDocumentService.DocumentView restore(@PathVariable Long id, @Valid @RequestBody RestoreRequest r, Authentication a, HttpServletRequest h) { return service.restore(id, r.revisionId, r.versionNo, (Long) a.getPrincipal(), h.getRequestId()); }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void archive(@PathVariable Long id, Authentication a, HttpServletRequest h) { service.archive(id, (Long) a.getPrincipal(), h.getRequestId()); }
    public record CreateRequest(@NotBlank @Size(max=255) String title, @NotBlank String documentType, Long teamId, List<@NotNull Long> skillIds, @NotBlank String markdownContent, Long parentId) {}
    public record UpdateRequest(@NotBlank @Size(max=255) String title, @NotBlank String markdownContent, int versionNo, List<@NotNull Long> skillIds, Long parentId, Boolean parentSpecified) {}
    public record RestoreRequest(@NotNull Long revisionId, int versionNo) {}
    public record DownloadRequest(@NotEmpty @Size(max=20) List<@NotNull @Positive Long> documentIds) {}
    public record DocumentMetadata(Long id, String title) {}
}
