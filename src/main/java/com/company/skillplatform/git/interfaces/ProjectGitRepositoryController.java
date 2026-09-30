package com.company.skillplatform.git.interfaces;

import com.company.skillplatform.git.application.GitStageWatchService;
import com.company.skillplatform.git.application.ProjectGitRepositoryService;
import com.company.skillplatform.git.domain.GitRemotePort;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ProjectGitRepositoryController {
    private final ProjectGitRepositoryService repositories; private final GitStageWatchService watches;
    public ProjectGitRepositoryController(ProjectGitRepositoryService repositories,GitStageWatchService watches){this.repositories=repositories;this.watches=watches;}
    @PostMapping("/projects/{projectKey}/git-repositories:resolve") public GitRemotePort.RepositoryResolution resolve(@PathVariable String projectKey,@RequestBody ResolveRequest request,Authentication auth){return repositories.resolve(projectKey,actor(auth),request.url());}
    @GetMapping("/projects/{projectKey}/git-repositories") public List<ProjectGitRepositoryService.RepositoryView> list(@PathVariable String projectKey,Authentication auth){return repositories.list(projectKey,actor(auth));}
    @PutMapping("/projects/{projectKey}/git-repositories") public List<ProjectGitRepositoryService.RepositoryView> replace(@PathVariable String projectKey,@RequestBody RepositoryListRequest request,Authentication auth){return repositories.replace(projectKey,actor(auth),request.repositories());}
    @PostMapping("/projects/{projectKey}/git-repositories:append") public List<ProjectGitRepositoryService.RepositoryView> append(@PathVariable String projectKey,@RequestBody ProjectGitRepositoryService.RepositoryCommand request,Authentication auth){return repositories.append(projectKey,actor(auth),request);}
    @GetMapping("/workflow-runs/{runId}/stages/{stageRunId}/git-status") public GitStageWatchService.GitStageStatus status(@PathVariable Long runId,@PathVariable Long stageRunId,Authentication auth){return watches.status(runId,stageRunId,actor(auth));}
    @PostMapping("/workflow-runs/{runId}/stages/{stageRunId}/git:refresh") public GitStageWatchService.GitStageStatus refresh(@PathVariable Long runId,@PathVariable Long stageRunId,Authentication auth){watches.refresh(runId,stageRunId,actor(auth));return watches.status(runId,stageRunId,actor(auth));}
    private Long actor(Authentication auth){return (Long)auth.getPrincipal();} public record ResolveRequest(String url){} public record RepositoryListRequest(List<ProjectGitRepositoryService.RepositoryCommand> repositories){}
}
