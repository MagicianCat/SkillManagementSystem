package com.company.skillplatform.ops;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/agent/operations")
public class OpsAlertController {
 private final AlertAdminService admin;private final JdbcTemplate db;
 public OpsAlertController(AlertAdminService admin,JdbcTemplate db){this.admin=admin;this.db=db;}
 @GetMapping("/alerts") @PreAuthorize("hasAuthority('admin:audit')")
 public Map<String,Object> list(@RequestParam(required=false) String state,@RequestParam(required=false) String severity,@RequestParam(required=false) String module,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return admin.page(state,severity,module,page,size);}
 @GetMapping("/alerts/{id}") @PreAuthorize("hasAuthority('admin:audit')") public Map<String,Object> detail(@PathVariable long id){return admin.detail(id);}
 @PostMapping("/alerts/{id}/ack") @PreAuthorize("hasAuthority('admin:alert:operate')") public Map<String,Object> ack(@PathVariable long id,@RequestBody AckRequest r,Authentication a){admin.acknowledge(id,actor(a),r.expectedVersion(),r.comment());return admin.detail(id);}
 @PostMapping("/alerts/{id}/silence") @PreAuthorize("hasAuthority('admin:alert:operate')") public Map<String,Object> silence(@PathVariable long id,@RequestBody SilenceRequest r,Authentication a){admin.silence(id,actor(a),r.expectedVersion(),r.silenceUntil(),r.reason());return admin.detail(id);}
 @PostMapping("/alerts/{id}/resolve") @PreAuthorize("hasAuthority('admin:alert:operate')") public Map<String,Object> resolve(@PathVariable long id,@RequestBody ResolveRequest r,Authentication a){admin.resolve(id,actor(a),r.expectedVersion(),r.reason());return admin.detail(id);}
 @GetMapping("/alert-config") @PreAuthorize("hasAuthority('admin:alert:configure')") public Map<String,Object> config(Authentication a){admin.requireAdmin(actor(a));return admin.config();}
 @PutMapping("/alert-config") @PreAuthorize("hasAuthority('admin:alert:configure')") public Map<String,Object> config(@RequestBody ConfigRequest r,Authentication a){admin.updateConfig(r.settingJson(),r.expectedVersion(),actor(a));return admin.config();}
 @GetMapping("/alert-recipients/candidates") @PreAuthorize("hasAuthority('admin:alert:configure')") public List<Map<String,Object>> candidates(@RequestParam(defaultValue="") String keyword,Authentication a){admin.requireAdmin(actor(a));return admin.candidates(keyword);}
 @PutMapping("/alert-recipients/{userId}") @PreAuthorize("hasAuthority('admin:alert:configure')") public Map<String,Object> recipient(@PathVariable long userId,@RequestBody RecipientRequest r,Authentication a){admin.setRecipient(userId,r.scopeCode(),r.minSeverity(),r.enabled(),r.expectedVersion(),actor(a));return admin.config();}
 @GetMapping("/alert-rules") @PreAuthorize("hasAuthority('admin:alert:configure')") public List<Map<String,Object>> rules(Authentication a){admin.requireAdmin(actor(a));return admin.rules();}
 @PutMapping("/alert-rules/{code}") @PreAuthorize("hasAuthority('admin:alert:configure')") public List<Map<String,Object>> rule(@PathVariable String code,@RequestBody RuleRequest r,Authentication a){admin.updateRule(code,r.enabled(),r.ruleConfigJson(),r.recoveryConfigJson(),r.expectedVersion(),actor(a));return admin.rules();}
 @PostMapping("/alert-config/test") @PreAuthorize("hasAuthority('admin:alert:configure')") public Map<String,String> test(@RequestBody TestRequest r,Authentication a){admin.test(r.recipientUserIds(),actor(a));return Map.of("status","SENT");}
 @GetMapping("/alert-config/me") @PreAuthorize("hasAuthority('admin:alert:configure')") public Map<String,Object> me(Authentication a){admin.requireAdmin(actor(a));return db.queryForMap("select id,display_name,case when feishu_open_id is not null then true else false end feishu_ready from iam_user where id=?",actor(a));}
 private long actor(Authentication a){return ((Number)a.getPrincipal()).longValue();}
 public record AckRequest(int expectedVersion,String comment){}
 public record SilenceRequest(int expectedVersion,Instant silenceUntil,String reason){}
 public record ResolveRequest(int expectedVersion,String reason){}
 public record ConfigRequest(int expectedVersion,String settingJson){}
 public record RecipientRequest(String scopeCode,String minSeverity,boolean enabled,Integer expectedVersion){}
 public record RuleRequest(boolean enabled,String ruleConfigJson,String recoveryConfigJson,int expectedVersion){}
 public record TestRequest(List<Long> recipientUserIds){}
}
