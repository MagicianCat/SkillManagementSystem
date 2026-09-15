package com.company.skillplatform.wiki.interfaces;

import com.company.skillplatform.common.interfaces.PageResponse;
import com.company.skillplatform.wiki.application.WikiDocumentReviewService;
import com.company.skillplatform.wiki.domain.WikiReviewStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WikiDocumentReviewController {
    private final WikiDocumentReviewService service;
    public WikiDocumentReviewController(WikiDocumentReviewService service) { this.service = service; }

    @PostMapping("/wiki/documents/{id}:submit-platform-review")
    @ResponseStatus(HttpStatus.CREATED)
    public WikiDocumentReviewService.ReviewView submit(@PathVariable Long id, @Valid @RequestBody SubmitRequest request, Authentication authentication, HttpServletRequest servletRequest) { return service.submit(id, request.versionNo, request.comment, (Long) authentication.getPrincipal(), servletRequest.getRequestId()); }
    @GetMapping("/wiki-reviews")
    public PageResponse<WikiDocumentReviewService.ReviewSummary> list(@RequestParam(required = false) WikiReviewStatus status, @RequestParam(required = false) String keyword, @RequestParam(required = false) Long teamId, Pageable pageable) { return PageResponse.from(service.list(status, keyword, teamId, pageable), value -> value); }
    @GetMapping("/wiki-reviews/{id}")
    public WikiDocumentReviewService.ReviewView get(@PathVariable Long id) { return service.get(id); }
    @PostMapping("/wiki-reviews/{id}:approve")
    public WikiDocumentReviewService.ReviewView approve(@PathVariable Long id, @Valid @RequestBody CommentRequest request, Authentication authentication, HttpServletRequest servletRequest) { return service.approve(id, request.comment, (Long) authentication.getPrincipal(), servletRequest.getRequestId()); }
    @PostMapping("/wiki-reviews/{id}:reject")
    public WikiDocumentReviewService.ReviewView reject(@PathVariable Long id, @Valid @RequestBody CommentRequest request, Authentication authentication, HttpServletRequest servletRequest) { return service.reject(id, request.comment, (Long) authentication.getPrincipal(), servletRequest.getRequestId()); }
    @PostMapping("/wiki-reviews/{id}:withdraw")
    public WikiDocumentReviewService.ReviewView withdraw(@PathVariable Long id, @RequestBody(required = false) CommentRequest request, Authentication authentication, HttpServletRequest servletRequest) { return service.withdraw(id, request == null ? null : request.comment, (Long) authentication.getPrincipal(), servletRequest.getRequestId()); }
    @PostMapping("/wiki-reviews:batch-approve")
    public WikiDocumentReviewService.BatchReviewView batchApprove(@Valid @RequestBody BatchRequest request, Authentication authentication, HttpServletRequest servletRequest) { return service.batch(request.reviewIds, request.comment, (Long) authentication.getPrincipal(), servletRequest.getRequestId(), true); }
    @PostMapping("/wiki-reviews:batch-reject")
    public WikiDocumentReviewService.BatchReviewView batchReject(@Valid @RequestBody BatchRequest request, Authentication authentication, HttpServletRequest servletRequest) { return service.batch(request.reviewIds, request.comment, (Long) authentication.getPrincipal(), servletRequest.getRequestId(), false); }

    public record SubmitRequest(@NotNull Integer versionNo, @NotBlank @Size(max = 2000) String comment) {}
    public record CommentRequest(@NotBlank @Size(max = 2000) String comment) {}
    public record BatchRequest(@NotEmpty @Size(max = 50) List<@NotNull Long> reviewIds, @NotBlank @Size(max = 2000) String comment) {}
}
