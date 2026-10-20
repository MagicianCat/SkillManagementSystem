package com.company.skillplatform.collabproject.asdm.interfaces;

import com.company.skillplatform.collabproject.asdm.application.CollabAsdmConfigurationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/collab-groups/{groupKey}/asdm")
public class CollabAsdmGroupController {
    private final CollabAsdmConfigurationService service;

    public CollabAsdmGroupController(CollabAsdmConfigurationService service) { this.service = service; }

    @GetMapping
    public CollabAsdmConfigurationService.AsdmStatus get(@PathVariable String groupKey, Authentication auth) {
        return service.get(groupKey, actor(auth));
    }

    @PutMapping("/credential")
    public CollabAsdmConfigurationService.CredentialView configureCredential(@PathVariable String groupKey,
            @Valid @RequestBody CredentialRequest request, Authentication auth) {
        return service.configureCredential(groupKey, actor(auth), request.projectAccessToken(), request.expectedVersion());
    }

    @PostMapping("/credential:revalidate")
    public CollabAsdmConfigurationService.CredentialView revalidate(@PathVariable String groupKey, Authentication auth) {
        return service.revalidate(groupKey, actor(auth));
    }

    @PutMapping("/repository")
    public CollabAsdmConfigurationService.AsdmStatus updateRepository(@PathVariable String groupKey,
            @Valid @RequestBody RepositoryRequest request, Authentication auth) {
        return service.updateRepository(groupKey, actor(auth), request.repositoryUrl(), request.expectedVersion());
    }

    private long actor(Authentication auth) { return ((Number) auth.getPrincipal()).longValue(); }

    public record CredentialRequest(@NotBlank @Size(max = 4096) String projectAccessToken, @Min(0) int expectedVersion) {}
    public record RepositoryRequest(@Size(max = 2048) String repositoryUrl, @Min(0) int expectedVersion) {}
}
