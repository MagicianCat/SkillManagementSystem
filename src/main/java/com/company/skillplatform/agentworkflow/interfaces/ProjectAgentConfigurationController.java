package com.company.skillplatform.agentworkflow.interfaces;
import com.company.skillplatform.agentworkflow.application.ProjectAgentConfigurationService;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/projects/{projectKey}/agent-configuration")
public class ProjectAgentConfigurationController {
 private final ProjectAgentConfigurationService service; public ProjectAgentConfigurationController(ProjectAgentConfigurationService s){service=s;}
 @GetMapping public ProjectAgentConfigurationService.ConfigurationView get(@PathVariable String projectKey,Authentication a){return service.get(projectKey,actor(a));}
 @PostMapping(":apply-preset") public ProjectAgentConfigurationService.ConfigurationView apply(@PathVariable String projectKey,@RequestBody Preset r,Authentication a){return service.applyPreset(projectKey,actor(a),r.presetVersionId());}
 @PostMapping(":validate") public ProjectAgentConfigurationService.ValidationView validate(@PathVariable String projectKey,Authentication a){return service.validate(projectKey,actor(a));}
 @PostMapping(":confirm") public ProjectAgentConfigurationService.ConfigurationView confirm(@PathVariable String projectKey,Authentication a){return service.confirm(projectKey,actor(a));}
 @PostMapping(":copy-from-project") public ProjectAgentConfigurationService.ConfigurationView copy(@PathVariable String projectKey,@RequestBody Copy r,Authentication a){return service.copy(projectKey,r.sourceProjectKey(),actor(a));}
 private Long actor(Authentication a){return (Long)a.getPrincipal();} public record Preset(Long presetVersionId){} public record Copy(String sourceProjectKey,String versionStrategy){}
}
