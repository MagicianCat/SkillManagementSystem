package com.company.skillplatform.agent.interfaces;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/agent/intents")
@PreAuthorize("hasAuthority('admin:audit')")
public class IntentAdminController {
    private static final Logger log=LoggerFactory.getLogger(IntentAdminController.class);
    private static final Set<String> POLICIES=Set.of("SKILL","WIKI","KNOWLEDGE","CHAT");
    private final JdbcTemplate jdbc;
    public IntentAdminController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping public List<Map<String,Object>> list(){return jdbc.queryForList("select intent_code,display_name,decision_type,instructions,yes_description,no_description,policy_key,min_yes_probability,enabled,sort_order,version_no,created_at,updated_at from agent_intent_definition order by sort_order,id");}
    @PostMapping public void create(@RequestBody Definition body, Authentication actor){validate(body);if(body.versionNo()!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"versionNo is server managed");
        jdbc.update("insert into agent_intent_definition(intent_code,display_name,decision_type,instructions,yes_description,no_description,policy_key,min_yes_probability,enabled,sort_order) values(?,?,?,?,?,?,?,?,?,?)",body.code(),body.displayName(),body.decisionType(),body.instructions(),body.yesDescription(),body.noDescription(),body.policyKey(),body.threshold(),body.enabled(),body.sortOrder());audit(actor,"create",body.code());}
    @PutMapping("/{code}") public void update(@PathVariable String code,@RequestBody Definition body,Authentication actor){validate(body);if(!code.equals(body.code())||body.versionNo()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"code or version mismatch");
        int changed=jdbc.update("update agent_intent_definition set display_name=?,decision_type=?,instructions=?,yes_description=?,no_description=?,policy_key=?,min_yes_probability=?,enabled=?,sort_order=?,version_no=version_no+1 where intent_code=? and version_no=?",body.displayName(),body.decisionType(),body.instructions(),body.yesDescription(),body.noDescription(),body.policyKey(),body.threshold(),body.enabled(),body.sortOrder(),code,body.versionNo());if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"definition changed");audit(actor,"update",code);}
    @PatchMapping("/{code}/status") public void status(@PathVariable String code,@RequestBody Status body,Authentication actor){if(body.versionNo()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"versionNo required");int changed=jdbc.update("update agent_intent_definition set enabled=?,version_no=version_no+1 where intent_code=? and version_no=?",body.enabled(),code,body.versionNo());if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"definition changed");audit(actor,"status",code);}
    private void validate(Definition body){if(body==null||body.code()==null||!body.code().matches("[A-Z][A-Z0-9_]{1,63}")||!Set.of("INTENT","CONSTRAINT").contains(body.decisionType())||!POLICIES.contains(body.policyKey())||body.displayName()==null||body.displayName().isBlank()||body.instructions()==null||body.instructions().isBlank()||body.yesDescription()==null||body.yesDescription().isBlank()||body.noDescription()==null||body.noDescription().isBlank()||body.threshold()==null||body.threshold()<0.5||body.threshold()>1||body.sortOrder()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid intent definition");}
    private void audit(Authentication actor,String action,String code){log.info("event=agent.intent_definition.changed actor={} action={} intentCode={}",actor==null?"unknown":actor.getName(),action,code);}
    public record Definition(String code,String displayName,String decisionType,String instructions,String yesDescription,String noDescription,String policyKey,Double threshold,Boolean enabled,Integer sortOrder,Integer versionNo){}
    public record Status(Boolean enabled,Integer versionNo){}
}
