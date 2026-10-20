package com.company.skillplatform.collabproject.interfaces;

import com.company.skillplatform.collabproject.application.CollabProjectService;
import com.company.skillplatform.collabproject.application.CollabFeishuBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.company.skillplatform.common.application.BusinessException;

@RestController
@RequestMapping("/api/v1/collab-projects")
public class CollabProjectController {
    private final CollabProjectService service;
    private final CollabFeishuBindingService feishuBinding;
    public CollabProjectController(CollabProjectService service,CollabFeishuBindingService feishuBinding){this.service=service;this.feishuBinding=feishuBinding;}

    @GetMapping("/creation-options")
    public CollabProjectService.CreationOptions creationOptions(Authentication auth){return new CollabProjectService.CreationOptions(service.creationOptions(actor(auth),service.isAdmin(actor(auth))));}
    @GetMapping("/users:search")
    public List<CollabProjectService.UserOption> users(@RequestParam(required=false) String q,Authentication auth){return service.searchUsers(q);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CollabProjectService.ProjectView create(@RequestBody(required=false) CreateRequest request,Authentication auth){throw new BusinessException("REQUIREMENT_GROUP_REQUIRED","Create requirements from a published project group",HttpStatus.UNPROCESSABLE_ENTITY);}
    @GetMapping public List<CollabProjectService.ProjectView> list(Authentication auth){return service.list(actor(auth));}
    @GetMapping("/{projectKey}") public CollabProjectService.ProjectView get(@PathVariable String projectKey,Authentication auth){return service.project(projectKey,actor(auth));}
    @GetMapping("/{projectKey}/members") public List<CollabProjectService.MemberView> members(@PathVariable String projectKey,Authentication auth){return service.members(projectKey,actor(auth));}
    @PatchMapping("/{projectKey}") public CollabProjectService.ProjectView update(@PathVariable String projectKey,@Valid @RequestBody UpdateRequest request,Authentication auth){return service.update(projectKey,actor(auth),request.name(),request.description(),request.approvalLevel(),request.expectedVersion());}
    @PostMapping("/{projectKey}/members") @ResponseStatus(HttpStatus.CREATED) public CollabProjectService.MemberView addMember(@PathVariable String projectKey,@Valid @RequestBody AddMemberRequest request,Authentication auth){return service.addMember(projectKey,actor(auth),request.userId());}
    @DeleteMapping("/{projectKey}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void removeMember(@PathVariable String projectKey,@PathVariable Long userId,Authentication auth){service.removeMember(projectKey,actor(auth),userId);}
    @PostMapping("/{projectKey}/ownership:transfer") public CollabProjectService.ProjectView transfer(@PathVariable String projectKey,@Valid @RequestBody TransferRequest request,Authentication auth){return service.transferOwnership(projectKey,actor(auth),request.newOwnerUserId());}
    @PutMapping("/{projectKey}/stages/{stageKey}/assignment") public CollabProjectService.StageView assignment(@PathVariable String projectKey,@PathVariable String stageKey,@Valid @RequestBody AssignmentRequest request,Authentication auth){return service.assign(projectKey,actor(auth),stageKey,request.ownerUserId(),request.reviewerUserId(),request.additionalReviewerUserIds());}
    @PutMapping("/{projectKey}/stages/{stageKey}/inclusion") public CollabProjectService.StageView inclusion(@PathVariable String projectKey,@PathVariable String stageKey,@Valid @RequestBody StageInclusionRequest request,Authentication auth){return service.setStageIncluded(projectKey,actor(auth),stageKey,request.included());}

    private Long actor(Authentication auth){return (Long)auth.getPrincipal();}

    public record CreateRequest(@NotNull Long homeTeamId,@NotBlank @Size(max=255) String name,@Size(max=2000) String description,@NotBlank String approvalLevel,@NotBlank @Size(max=2048) String wikiRootUrl,List<@Valid CreateAssignmentRequest> stageAssignments,List<Long> memberUserIds,List<String> includedStageKeys){}
    public record CreateAssignmentRequest(@NotBlank String stageKey,@NotNull Long ownerUserId,Long reviewerUserId){}
    public record AssignmentRequest(@NotNull Long ownerUserId,Long reviewerUserId,List<Long> additionalReviewerUserIds){}
    public record StageInclusionRequest(boolean included){}
    public record UpdateRequest(@NotBlank @Size(max=255) String name,@Size(max=2000) String description,String approvalLevel,@Min(0) int expectedVersion){}
    public record AddMemberRequest(@NotNull Long userId){}
    public record TransferRequest(@NotNull Long newOwnerUserId){}
}
