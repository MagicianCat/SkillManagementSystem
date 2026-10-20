package com.company.skillplatform.collabproject.group.interfaces;

import com.company.skillplatform.collabproject.group.application.CollabGroupService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/collab-groups")
public class CollabGroupController {
    private final CollabGroupService service;
    public CollabGroupController(CollabGroupService service){this.service=service;}

    @GetMapping("/creation-options")
    public List<CollabGroupService.TeamOption> creationOptions(Authentication auth){return service.creationOptions(actor(auth));}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public CollabGroupService.GroupView create(@Valid @RequestBody CreateRequest request,Authentication auth){return service.create(new CollabGroupService.CreateGroup(request.homeTeamId(),request.name(),request.description(),request.gitlabRootUrl(),request.asdmRepositoryUrl()),actor(auth));}
    @GetMapping public List<CollabGroupService.GroupView> list(Authentication auth){return service.list(actor(auth));}
    @GetMapping("/{groupKey}") public CollabGroupService.GroupView get(@PathVariable String groupKey,Authentication auth){return service.get(groupKey,actor(auth));}
    @PatchMapping("/{groupKey}") public CollabGroupService.GroupView update(@PathVariable String groupKey,@Valid @RequestBody UpdateRequest request,Authentication auth){return service.update(groupKey,actor(auth),new CollabGroupService.UpdateGroup(request.name(),request.description(),request.gitlabRootUrl(),request.expectedVersion()));}

    @PostMapping("/{groupKey}/members") @ResponseStatus(HttpStatus.CREATED)
    public CollabGroupService.MemberView addMember(@PathVariable String groupKey,@Valid @RequestBody MemberRequest request,Authentication auth){return service.addMember(groupKey,actor(auth),request.userId());}
    @DeleteMapping("/{groupKey}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable String groupKey,@PathVariable Long userId,Authentication auth){service.removeMember(groupKey,actor(auth),userId);}

    @PostMapping("/{groupKey}/roles") @ResponseStatus(HttpStatus.CREATED)
    public CollabGroupService.RoleView createRole(@PathVariable String groupKey,@Valid @RequestBody RoleRequest request,Authentication auth){return service.createRole(groupKey,actor(auth),request.roleName());}
    @PatchMapping("/{groupKey}/roles/{roleKey}")
    public CollabGroupService.RoleView renameRole(@PathVariable String groupKey,@PathVariable String roleKey,@Valid @RequestBody RoleRequest request,Authentication auth){return service.renameRole(groupKey,actor(auth),roleKey,request.roleName());}
    @DeleteMapping("/{groupKey}/roles/{roleKey}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRole(@PathVariable String groupKey,@PathVariable String roleKey,Authentication auth){service.deleteRole(groupKey,actor(auth),roleKey);}
    @PutMapping("/{groupKey}/roles/{roleKey}/members")
    public CollabGroupService.RoleView replaceRoleMembers(@PathVariable String groupKey,@PathVariable String roleKey,@Valid @RequestBody RoleMembersRequest request,Authentication auth){return service.replaceRoleMembers(groupKey,actor(auth),roleKey,request.userIds());}

    @PutMapping("/{groupKey}/stages/{stageKey}/template")
    public CollabGroupService.StageTemplateView setStageTemplate(@PathVariable String groupKey,@PathVariable String stageKey,@Valid @RequestBody StageTemplateRequest request,Authentication auth){return service.setStageTemplate(groupKey,actor(auth),stageKey,new CollabGroupService.StageTemplateCommand(request.ownerRoleKey(),request.reviewerRoleKey(),request.reviewerRoleKeys(),request.included(),request.expectedVersion()));}
    @GetMapping("/{groupKey}/template-preview")
    public CollabGroupService.TemplatePreview preview(@PathVariable String groupKey,Authentication auth){return service.preview(groupKey,actor(auth));}
    @PostMapping("/{groupKey}:publish")
    public CollabGroupService.GroupView publish(@PathVariable String groupKey,@Valid @RequestBody PublishRequest request,Authentication auth){return service.publish(groupKey,actor(auth),request.expectedVersion());}

    @GetMapping("/{groupKey}/feishu-binding") public CollabGroupService.BindingView binding(@PathVariable String groupKey,Authentication auth){return service.binding(groupKey,actor(auth));}
    @PutMapping("/{groupKey}/feishu-binding") public CollabGroupService.BindingView configureWiki(@PathVariable String groupKey,@Valid @RequestBody WikiRootRequest request,Authentication auth){return service.configureWikiRoot(groupKey,actor(auth),request.url());}

    @GetMapping("/{groupKey}/requirements") public List<CollabGroupService.RequirementView> requirements(@PathVariable String groupKey,Authentication auth){return service.requirements(groupKey,actor(auth));}
    @PostMapping("/{groupKey}/requirements") @ResponseStatus(HttpStatus.CREATED)
    public CollabGroupService.RequirementView createRequirement(@PathVariable String groupKey,@Valid @RequestBody RequirementRequest request,Authentication auth){return service.createRequirement(groupKey,actor(auth),new CollabGroupService.CreateRequirement(request.name(),request.description(),request.approvalLevel(),request.includedStageKeys(),request.stagesWithoutApproval(),request.expectedTemplateVersion(),request.asdmBranch()));}

    private long actor(Authentication auth){return ((Number)auth.getPrincipal()).longValue();}
    public record CreateRequest(@NotNull Long homeTeamId,@NotBlank @Size(max=255) String name,@Size(max=2000) String description,@Size(max=2048) String gitlabRootUrl,@Size(max=2048) String asdmRepositoryUrl){}
    public record UpdateRequest(@NotBlank @Size(max=255) String name,@Size(max=2000) String description,@Size(max=2048) String gitlabRootUrl,@Min(0) int expectedVersion){}
    public record MemberRequest(@NotNull Long userId){}
    public record RoleRequest(@NotBlank @Size(max=100) String roleName){}
    public record RoleMembersRequest(List<@NotNull Long> userIds){}
    public record StageTemplateRequest(String ownerRoleKey,String reviewerRoleKey,List<String> reviewerRoleKeys,boolean included,@Min(0) int expectedVersion){}
    public record PublishRequest(@Min(0) int expectedVersion){}
    public record WikiRootRequest(@NotBlank @Size(max=2048) String url){}
    public record RequirementRequest(@NotBlank @Size(max=255) String name,@Size(max=2000) String description,@NotBlank String approvalLevel,@NotEmpty List<String> includedStageKeys,List<String> stagesWithoutApproval,@Min(1) int expectedTemplateVersion,String asdmBranch){}
}
