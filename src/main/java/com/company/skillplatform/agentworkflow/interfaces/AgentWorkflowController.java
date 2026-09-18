package com.company.skillplatform.agentworkflow.interfaces;
import com.company.skillplatform.agentworkflow.application.AgentWorkflowService;
import com.company.skillplatform.agentworkflow.application.WorkflowSseService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class AgentWorkflowController {
 private final AgentWorkflowService service; private final WorkflowSseService sse; public AgentWorkflowController(AgentWorkflowService s,WorkflowSseService sse){service=s;this.sse=sse;}
 @GetMapping("/agent-profiles") public List<AgentWorkflowService.ProfileView> profiles(){return service.profiles();}
 @GetMapping("/agent-profiles/{code}") public AgentWorkflowService.ProfileView profile(@PathVariable String code){return service.profile(code);}
 @PostMapping("/agent-profiles") public AgentWorkflowService.ProfileView profile(@Valid @RequestBody ProfileRequest r,Authentication a){return service.createProfile(new AgentWorkflowService.ProfileCommand(r.code(),r.name(),r.category(),r.description()),String.valueOf(actor(a)));}
 @PostMapping("/agent-profiles/{code}/versions") public AgentWorkflowService.VersionView version(@PathVariable String code,@RequestBody VersionRequest r,Authentication a){return service.createVersion(code,new AgentWorkflowService.VersionCommand(r.systemPrompt(),r.modelCode(),r.temperature(),r.maxIterationPerRun()==null?30:r.maxIterationPerRun(),r.timeoutSeconds()==null?1800:r.timeoutSeconds(),r.outputSchemaJson(),r.runtimeConfigJson(),r.changelog(),r.skillIds(),r.tools()),String.valueOf(actor(a)));}
 @PostMapping("/agent-profiles/{code}/versions/{versionNo}:publish") public AgentWorkflowService.VersionView publish(@PathVariable String code,@PathVariable int versionNo,Authentication a){return service.publish(code,versionNo,String.valueOf(actor(a)));}
 @PostMapping("/projects/{projectKey}/workflow-runs") public AgentWorkflowService.RunView start(@PathVariable String projectKey,@RequestBody RunRequest r,Authentication a){var c=new AgentWorkflowService.RunCommand(r.workflowCode()==null?"requirement-mvp":r.workflowCode(),r.workflowVersion()==null?1:r.workflowVersion(),r.profileVersionIds()==null?List.of():r.profileVersionIds(),r.initialRequest(),r.contextSnapshotJson());return service.start(projectKey,actor(a),c);}
 @GetMapping("/projects/{projectKey}/workflow-runs/current") public AgentWorkflowService.RunView current(@PathVariable String projectKey,Authentication a){return service.current(projectKey,actor(a));}
 @GetMapping("/workflow-runs/{runId}") public AgentWorkflowService.RunView run(@PathVariable Long runId,Authentication a){return service.run(runId,actor(a));}
 @PostMapping("/workflow-runs/{runId}/interventions") public AgentWorkflowService.InterventionView intervention(@PathVariable Long runId,@RequestBody InterventionRequest r,Authentication a){return service.interveneWithCommand(runId,actor(a),new AgentWorkflowService.InterventionCommand(r.type(),r.content(),r.stageRunId(),r.agentSessionId(),r.agentRunId()));}
 @PostMapping("/workflow-runs/{runId}/final-acceptance") public AgentWorkflowService.RunView accept(@PathVariable Long runId,@RequestBody AcceptanceRequest r,Authentication a){String decision="REWORK".equalsIgnoreCase(r.decision())?"REJECTED":"ACCEPT".equalsIgnoreCase(r.decision())?"ACCEPTED":r.decision();return service.accept(runId,actor(a),decision,r.comment());}
 @PostMapping("/workflow-runs/{runId}/design-acceptance") public AgentWorkflowService.RunView designAcceptance(@PathVariable Long runId,@RequestBody DesignAcceptanceRequest r,Authentication a){return service.designAcceptance(runId,actor(a),r.decision(),r.targetStageKey(),r.comment());}
 @GetMapping(value="/workflow-runs/{runId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter events(@PathVariable Long runId,@RequestHeader(value="Last-Event-ID",required=false)String headerId,@RequestParam(value="lastEventId",required=false)String queryId,Authentication a){var snapshot=service.run(runId,actor(a));return sse.subscribe(runId,sequence(headerId==null?queryId:headerId),snapshot);}
 private Long actor(Authentication a){return (Long)a.getPrincipal();}
 private long sequence(String value){try{return value==null?0:Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}
 public record ProfileRequest(String code,String name,String category,String description){} public record VersionRequest(String systemPrompt,String modelCode,Double temperature,Integer maxIterationPerRun,Integer timeoutSeconds,String outputSchemaJson,String runtimeConfigJson,String changelog,List<Long> skillIds,List<AgentWorkflowService.ToolBinding> tools){} public record RunRequest(String workflowCode,Integer workflowVersion,List<Long> profileVersionIds,String initialRequest,String contextSnapshotJson){} public record InterventionRequest(String type,String content,Long stageRunId,Long agentSessionId,Long agentRunId){} public record AcceptanceRequest(String decision,String comment){} public record DesignAcceptanceRequest(String decision,String targetStageKey,String comment){}
}
