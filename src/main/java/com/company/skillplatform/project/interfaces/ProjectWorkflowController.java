package com.company.skillplatform.project.interfaces;

import com.company.skillplatform.project.application.ProjectWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectWorkflowController {
    private final ProjectWorkflowService service;
    public ProjectWorkflowController(ProjectWorkflowService service){this.service=service;}
    @GetMapping("/{projectKey}/workflow") public ProjectWorkflowService.WorkflowView workflow(@PathVariable String projectKey,Authentication auth){return service.workflow(projectKey,actor(auth));}
    @PutMapping("/{projectKey}/members/{userId}/functional-roles") public List<ProjectWorkflowService.MemberRoleView> roles(@PathVariable String projectKey,@PathVariable Long userId,@RequestBody Roles request,Authentication auth){return service.assignRoles(projectKey,userId,request.roles(),actor(auth));}
    @PutMapping("/{projectKey}/stages/{stageKey}/skills") public ProjectWorkflowService.StageView skills(@PathVariable String projectKey,@PathVariable String stageKey,@RequestBody Skills request,Authentication auth){return service.configureSkills(projectKey,stageKey.toUpperCase(Locale.ROOT),request.skillKeys(),actor(auth));}
    @PostMapping("/{projectKey}/stages/{stageKey}:start") public ProjectWorkflowService.StageView start(@PathVariable String projectKey,@PathVariable String stageKey,Authentication auth){return service.start(projectKey,stageKey.toUpperCase(Locale.ROOT),actor(auth));}
    @PostMapping("/{projectKey}/stages/{stageKey}/submissions") public ProjectWorkflowService.SubmissionView submit(@PathVariable String projectKey,@PathVariable String stageKey,@Valid @RequestBody Submission request,Authentication auth){return service.submit(projectKey,stageKey.toUpperCase(Locale.ROOT),request.documentId(),request.revisionId(),actor(auth));}
    @PostMapping("/{projectKey}/stages/{stageKey}/submissions/{submissionId}:review") public ProjectWorkflowService.SubmissionView review(@PathVariable String projectKey,@PathVariable String stageKey,@PathVariable Long submissionId,@RequestBody Review request,Authentication auth){return service.review(projectKey,stageKey.toUpperCase(Locale.ROOT),submissionId,request.decision().toUpperCase(Locale.ROOT),request.comment(),actor(auth));}
    @GetMapping("/users:search") public List<ProjectWorkflowService.UserSearchView> users(@RequestParam(defaultValue="") String q,Authentication auth){return service.searchUsers(q,actor(auth));}
    private Long actor(Authentication auth){return (Long)auth.getPrincipal();}
    public record Roles(Set<String> roles){}
    public record Skills(List<String> skillKeys){}
    public record Submission(@NotNull Long documentId,@NotNull Long revisionId){}
    public record Review(@NotNull String decision,String comment){}
}
