package com.company.skillplatform.agentworkflow.interfaces;
import com.company.skillplatform.agentworkflow.application.ProjectAgentConfigurationService;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/projects/{projectKey}")
public class ProjectAgentConfigurationController {
 private final ProjectAgentConfigurationService service; public ProjectAgentConfigurationController(ProjectAgentConfigurationService s){service=s;}
 @GetMapping("/agent-configuration") public ProjectAgentConfigurationService.ConfigurationView get(@PathVariable String projectKey,Authentication a){return service.get(projectKey,actor(a));}
 @PostMapping("/agent-configuration:apply-preset") public ProjectAgentConfigurationService.ConfigurationView apply(@PathVariable String projectKey,@RequestBody Preset r,Authentication a){return service.applyPreset(projectKey,actor(a),r.presetVersionId());}
 @PostMapping("/agent-configuration:validate") public ProjectAgentConfigurationService.ValidationView validate(@PathVariable String projectKey,Authentication a){return service.validate(projectKey,actor(a));}
 @PostMapping("/agent-configuration:confirm") public ProjectAgentConfigurationService.ConfigurationView confirm(@PathVariable String projectKey,Authentication a){return service.confirm(projectKey,actor(a));}
 @PostMapping("/agent-configuration:copy-from-project") public ProjectAgentConfigurationService.ConfigurationView copy(@PathVariable String projectKey,@RequestBody Copy r,Authentication a){if(r.versionStrategy()!=null&&!"EXACT".equalsIgnoreCase(r.versionStrategy()))throw new IllegalArgumentException("Only EXACT copy is supported");return service.copy(projectKey,r.sourceProjectKey(),actor(a));}
 @PutMapping("/agent-configuration/nodes/{stageKey}/{nodeKey}") public ProjectAgentConfigurationService.ConfigurationView node(@PathVariable String projectKey,@PathVariable String stageKey,@PathVariable String nodeKey,@RequestBody Node r,Authentication a){return service.replaceNode(projectKey,actor(a),stageKey,nodeKey,r.agentProfileVersionId());}
 @GetMapping("/agent-configuration/reusable-projects") public List<ProjectAgentConfigurationService.ReusableProject> reusable(Authentication a){return service.reusable((Long)a.getPrincipal());}
 private Long actor(Authentication a){return (Long)a.getPrincipal();} public record Preset(Long presetVersionId){} public record Copy(String sourceProjectKey,String versionStrategy){} public record Node(Long agentProfileVersionId){}
}
