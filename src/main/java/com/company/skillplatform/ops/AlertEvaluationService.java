package com.company.skillplatform.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Evaluates persisted rules over persisted facts. Missing samples never imply health. */
@Service
public class AlertEvaluationService {
 private static final Logger log=LoggerFactory.getLogger(AlertEvaluationService.class);
 private final JdbcTemplate db;private final ObjectMapper json;private final TransactionTemplate tx;private final OpsAlertService lifecycle;
 public AlertEvaluationService(JdbcTemplate db,ObjectMapper json,TransactionTemplate tx,OpsAlertService lifecycle){this.db=db;this.json=json;this.tx=tx;this.lifecycle=lifecycle;}
 @Scheduled(fixedDelayString="${skill-platform.alerting.evaluation-delay-ms:60000}")
 public void evaluate(){
  for(Map<String,Object> rule:db.queryForList("select * from ops_alert_rule where enabled=true")){
   try{
    JsonNode config=json.readTree(String.valueOf(rule.get("rule_config_json")));
    String type=config.path("eventType").asText();if(type.isBlank())continue;
    List<String> scopes;
    String code=String.valueOf(rule.get("rule_code"));
    if("TELEMETRY_SEVERE_CONSECUTIVE".equals(code))scopes=db.query("select distinct concat(actor_user_id,':',coalesce(client_installation_id,'UNKNOWN')) from ops_telemetry_quality where assessed_at>date_sub(now(3),interval 1 day)",(r,n)->r.getString(1));
    else if("TELEMETRY_PLATFORM_INCOMPLETE_RATE".equals(code))scopes=List.of("GLOBAL");
    else scopes=db.query("select distinct fingerprint from ops_event where event_type=? and occurred_at>date_sub(now(3),interval 1 day)",(r,n)->r.getString(1),type);
    for(String scope:scopes)tx.executeWithoutResult(status->evaluateScope(rule,config,scope));
   }catch(Exception ex){log.error("[Yanshu][ALERT] event=alert.rule.evaluation_failed ruleCode={}",rule.get("rule_code"),ex);}
  }
 }
 void evaluateScope(Map<String,Object> rule,JsonNode config,String scope){
  long ruleId=((Number)rule.get("id")).longValue();String eventType=config.path("eventType").asText();
  int window=Math.max(60,Math.min(86400,config.path("windowSeconds").asInt(900)));
  List<Map<String,Object>> rows;
  String code=String.valueOf(rule.get("rule_code"));
  if("TELEMETRY_SEVERE_CONSECUTIVE".equals(code)){
   String[] parts=scope.split(":",2);if(parts.length!=2)return;
   rows=db.queryForList("select e.id,case when q.quality_grade='SEVERE_INCOMPLETE' then 'FAILED' else 'SUCCESS' end outcome,e.error_code,e.occurred_at,e.trace_id from ops_telemetry_quality q join ops_event e on e.event_key=concat('telemetry-quality:',q.generation_event_id,':revision:',q.capture_revision) where q.actor_user_id=? and coalesce(q.client_installation_id,'UNKNOWN')=? and q.assessed_at>=date_sub(now(3),interval 1 day) order by q.generation_started_at desc,q.generation_event_id desc limit 50",Long.parseLong(parts[0]),parts[1]);
  }else if("TELEMETRY_PLATFORM_INCOMPLETE_RATE".equals(code)){
   rows=db.queryForList("select e.id,case when q.quality_grade='SEVERE_INCOMPLETE' then 'FAILED' else 'SUCCESS' end outcome,e.error_code,e.occurred_at,e.trace_id from ops_telemetry_quality q join ops_event e on e.event_key=concat('telemetry-quality:',q.generation_event_id,':revision:',q.capture_revision) where q.assessed_at>=date_sub(now(3),interval ? second) order by q.assessed_at desc limit 1000",window);
  }else rows=db.queryForList("select id,outcome,error_code,occurred_at,trace_id,duration_ms from ops_event where event_type=? and fingerprint=? and occurred_at>=date_sub(now(3),interval ? second) order by id desc limit 1000",eventType,scope,window);
  if(rows.isEmpty())return;
  int failures=0,successes=0,consecutiveFailures=0,consecutiveSuccesses=0;
  for(Map<String,Object> row:rows){String outcome=String.valueOf(row.get("outcome"));if("FAILED".equals(outcome)||"DEGRADED".equals(outcome)||"BLOCKED".equals(outcome))failures++;else if("SUCCESS".equals(outcome))successes++;}
  for(Map<String,Object> row:rows){if(!"SUCCESS".equals(row.get("outcome")))break;consecutiveSuccesses++;}
  for(Map<String,Object> row:rows){if("SUCCESS".equals(row.get("outcome")))break;consecutiveFailures++;}
  String type=String.valueOf(rule.get("rule_type"));boolean triggered=switch(type){
   case "EVENT_ONCE" -> !"SUCCESS".equals(rows.get(0).get("outcome"));
   case "CONSECUTIVE_FAILURE","HEALTH" -> consecutiveFailures>=config.path("consecutiveFailures").asInt(3);
   case "WINDOW_RATE" -> rows.size()>=config.path("minimumSamples").asInt(10)&&failures/(double)rows.size()>=config.path("failureRateGreaterOrEqual").asDouble(1);
   case "WINDOW_COUNT" -> failures>=config.path("minimumFailures").asInt(3);
   case "STATE_STALLED" -> !"SUCCESS".equals(rows.get(0).get("outcome"));
   default -> false;
  };
  if("DSH_SYSTEM_UNAVAILABLE".equals(code)&&triggered){Integer traffic=db.queryForObject("select count(*) from ops_event where event_type='agent.dsh.create' and occurred_at>date_sub(now(3),interval 5 minute)",Integer.class);if(traffic==null||traffic==0)triggered=false;}
  List<Map<String,Object>> active=db.queryForList("select * from ops_alert_instance where rule_id=? and scope_code=? and active_fingerprint is not null for update",ruleId,scope);
  long latestId=((Number)rows.get(0).get("id")).longValue();
  if(triggered){
   String severity=String.valueOf(rule.get("severity"));int escalateAt=config.path("escalateAt").asInt(Integer.MAX_VALUE);if(consecutiveFailures>=escalateAt)severity="P0";if("STATE_STALLED".equals(type)&&rows.get(0).get("duration_ms") instanceof Number duration&&duration.longValue()>=config.path("escalateMinutes").asLong(Long.MAX_VALUE)*60000L)severity="P0";
   String root=rows.get(0).get("error_code")==null?"UNKNOWN":String.valueOf(rows.get(0).get("error_code"));
   if(active.isEmpty()){
    String fp=OpsEventService.fingerprint("default",String.valueOf(rule.get("rule_code")),scope,root);
    try{
     db.update("insert into ops_alert_instance(alert_no,fingerprint,active_fingerprint,rule_id,rule_code,module,scope_code,severity,status,title,summary,error_code,latest_event_id,trace_id,first_seen_at,last_seen_at,last_evaluated_at,last_evaluated_event_id,occurrence_count,next_reminder_at,rule_snapshot_json,notification_enabled) values(?,?,?,?,?,?,?,?,'OPEN',?,?,?,?,?,now(3),now(3),now(3),?,?,date_add(now(3),interval ? minute),?,?)",
      "ALT-"+java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-","")+"-"+ruleId+"-"+latestId,fp,fp,ruleId,rule.get("rule_code"),rule.get("module"),scope,severity,rule.get("display_name"),"scope="+scope+", failures="+failures+"/"+rows.size(),root,latestId,rows.get(0).get("trace_id"),latestId,Math.max(1,failures),initialDelay(),String.valueOf(rule.get("rule_config_json")),config.path("notificationEnabled").asBoolean(true));
    }catch(org.springframework.dao.DuplicateKeyException conflict){return;}
    long alertId=db.queryForObject("select id from ops_alert_instance where active_fingerprint=?",Long.class,fp);
    lifecycle.enqueueBatch(alertId,"OPEN",0);
    log.warn("[Yanshu][ALERT] event=alert.instance.opened ruleCode={} scope={} alertId={}",rule.get("rule_code"),scope,alertId);
   }else{
    Map<String,Object> incident=active.get(0);if("P0".equals(incident.get("severity")))severity="P0";long id=((Number)incident.get("id")).longValue();boolean escalate="P0".equals(severity)&&!"P0".equals(incident.get("severity"));
    db.update("update ops_alert_instance set latest_event_id=?,last_seen_at=now(3),last_evaluated_at=now(3),occurrence_count=occurrence_count+?,severity=?,summary=?,recovery_count=0,last_evaluated_event_id=?,time_updated=now(3) where id=?",latestId,latestId>number(incident.get("last_evaluated_event_id"))?1:0,severity,"scope="+scope+", failures="+failures+"/"+rows.size(),latestId,id);
    if(escalate)lifecycle.enqueueBatch(id,"ESCALATION",0);
   }
  }else if(!active.isEmpty()){
   JsonNode recovery;try{recovery=json.readTree(String.valueOf(rule.get("recovery_config_json")));}catch(Exception ex){return;}
   int needed=recovery.path("consecutiveSuccesses").asInt(0);
   boolean healthy=needed>0?consecutiveSuccesses>=needed:rows.size()>=recovery.path("minimumSamples").asInt(10)&&failures/(double)rows.size()<recovery.path("failureRateBelow").asDouble(0);
   for(Map<String,Object> incident:active){long id=((Number)incident.get("id")).longValue();if(!healthy){db.update("update ops_alert_instance set recovery_count=0,last_evaluated_at=now(3) where id=?",id);continue;}
    int count=((Number)incident.get("recovery_count")).intValue()+1;
    if(count<recovery.path("healthyWindows").asInt(1)){db.update("update ops_alert_instance set recovery_count=?,last_evaluated_at=now(3) where id=?",count,id);continue;}
    lifecycle.resolveAutomatically(id,"AUTO_RECOVERY");
   }
  }
 }
 private int initialDelay(){Integer value=db.queryForObject("select cast(json_unquote(json_extract(setting_json,'$.initialMinutes')) as unsigned) from ops_alert_settings where setting_key='DEFAULT_ALERT_NOTIFICATION'",Integer.class);return value==null?5:value;}
 private long number(Object value){return value instanceof Number n?n.longValue():0;}
}
