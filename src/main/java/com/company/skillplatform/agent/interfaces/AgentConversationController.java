package com.company.skillplatform.agent.interfaces;
import com.company.skillplatform.agent.application.AgentConversationService;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;
import org.springframework.http.*;import org.springframework.security.access.prepost.PreAuthorize;import org.springframework.security.core.Authentication;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
@RestController @RequestMapping("/api/v1/agent") @PreAuthorize("hasAuthority('skill:browse')")
public class AgentConversationController{
 private final AgentConversationService service;public AgentConversationController(AgentConversationService service){this.service=service;}
 @GetMapping("/profiles")List<AgentConversationService.ProfileView>profiles(){return service.profiles();}
 @PostMapping("/sessions")AgentConversationService.SessionView create(@Valid@RequestBody CreateSession r,Authentication a){return service.create((Long)a.getPrincipal(),r.profileKey,r.context==null?null:r.context.platform,r.context==null?null:r.context.osType);}
 @GetMapping("/sessions")List<AgentConversationService.SessionSummary>list(Authentication a){return service.list((Long)a.getPrincipal());}
 @GetMapping("/sessions/{key}")AgentConversationService.SessionView get(@PathVariable String key,Authentication a){return service.get(key,(Long)a.getPrincipal());}
 @DeleteMapping("/sessions/{key}")@ResponseStatus(HttpStatus.NO_CONTENT)void close(@PathVariable String key,Authentication a){service.close(key,(Long)a.getPrincipal());}
 @PostMapping("/sessions/{key}/messages")ResponseEntity<AgentConversationService.AcceptedRun>send(@PathVariable String key,@Valid@RequestBody SendMessage r,Authentication a){return ResponseEntity.accepted().body(service.send(key,(Long)a.getPrincipal(),r.content));}
 @GetMapping(value="/runs/{key}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)SseEmitter events(@PathVariable String key,@RequestHeader(value="Last-Event-ID",required=false)String last,Authentication a){return service.subscribe(key,(Long)a.getPrincipal(),lastEventId(last));}
 @GetMapping("/runs/{key}")AgentConversationService.RunView run(@PathVariable String key,Authentication a){return service.run(key,(Long)a.getPrincipal());}
 @PostMapping("/runs/{key}:cancel")@ResponseStatus(HttpStatus.ACCEPTED)void cancel(@PathVariable String key,Authentication a){service.cancel(key,(Long)a.getPrincipal());}
 public record Context(String platform,String osType){} public record CreateSession(@NotBlank String profileKey,Context context){} public record SendMessage(@NotBlank@Size(max=10000)String content){}
 private long lastEventId(String value){if(value==null||value.isBlank())return 0;try{return Math.max(0,Long.parseLong(value));}catch(NumberFormatException ignored){return 0;}}
}
