package com.company.skillplatform.collabproject.interfaces;

import com.company.skillplatform.collabproject.application.CollabFeishuBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/collab-projects/{projectKey}")
public class CollabFeishuBindingController {
    private final CollabFeishuBindingService service;

    public CollabFeishuBindingController(CollabFeishuBindingService service) { this.service = service; }

    @GetMapping("/feishu")
    public CollabFeishuBindingService.BindingView get(@PathVariable String projectKey, Authentication auth) {
        return service.get(projectKey, (Long) auth.getPrincipal());
    }

    @PutMapping("/feishu/wiki-root")
    public CollabFeishuBindingService.BindingView configure(@PathVariable String projectKey,
                                                              @Valid @RequestBody WikiRootRequest request, Authentication auth) {
        return service.configureWikiRoot(projectKey, (Long) auth.getPrincipal(), request.url());
    }

    @PostMapping("/feishu:retry")
    public CollabFeishuBindingService.BindingView retry(@PathVariable String projectKey, Authentication auth) {
        return service.retry(projectKey, (Long) auth.getPrincipal());
    }

    public record WikiRootRequest(@NotBlank String url) {}
}
