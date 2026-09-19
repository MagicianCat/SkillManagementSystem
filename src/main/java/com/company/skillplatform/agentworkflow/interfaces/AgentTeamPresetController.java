package com.company.skillplatform.agentworkflow.interfaces;
import com.company.skillplatform.agentworkflow.application.ProjectAgentConfigurationService;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/agent-team-presets")
public class AgentTeamPresetController {private final ProjectAgentConfigurationService service;public AgentTeamPresetController(ProjectAgentConfigurationService s){service=s;}@GetMapping public List<ProjectAgentConfigurationService.PresetView> list(){return service.presets();}}
