package com.company.skillplatform.ops;

import com.company.skillplatform.collabproject.application.CollabFeishuCardLayout;
import com.company.skillplatform.collabproject.application.CollabFeishuChatGateway;
import com.company.skillplatform.common.application.BusinessException;
import com.company.skillplatform.common.logging.LogContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertAdminService {
 private final JdbcTemplate db;private final ObjectMapper json;private final CollabFeishuChatGateway feishu;private final OpsAlertService lifecycle;
 public AlertAdminService(JdbcTemplate db,ObjectMapper json,CollabFeishuChatGateway feishu,OpsAlertService lifecycle){this.db=db;this.json=json;this.feishu=feishu;this.lifecycle=lifecycle;}
 public Map<String,Object> page(String state,String severity,String module,int page,int size){
  if(state!=null&&!List.of("OPEN","ACKED","RESOLVED").contains(state))throw invalid("Invalid state");
  if(severity!=null&&!List.of("P0","P1","P2").contains(severity))throw invalid("Invalid severity");
  if(module!=null&&!module.matches("[A-Za-z0-9_]{1,48}"))throw invalid("Invalid module");
  page=Math.max(0,page);size=Math.min(100,Math.max(1,size));
  String where=" where (? is null or status=?) and (? is null or severity=?) and (? is null or module=?)";
  long total=db.queryForObject("select count(*) from ops_alert_instance"+where,Long.class,state,state,severity,severity,module,module);
  List<Map<String,Object>> items=db.queryForList("select id,alert_no,rule_code,module,scope_code,severity,status as state,title,summary,first_seen_at,last_seen_at,occurrence_count,next_reminder_at,acknowledged_by from ops_alert_instance"+where+" order by id desc limit ? offset ?",state,state,severity,severity,module,module,size,page*size);
  return Map.of("items",items,"totalElements",total,"totalPages",(total+size-1)/size,"size",size,"number",page);
 }
 public Map<String,Object> detail(long id){List<Map<String,Object>> alerts=db.queryForList("select * from ops_alert_instance where id=?",id);if(alerts.isEmpty())throw missing();
  Map<String,Object> data=new java.util.LinkedHashMap<>(alerts.get(0));data.put("state",data.get("status"));
  data.put("events",db.queryForList("select id,event_type,outcome,occurred_at,trace_id,run_key,error_code,phase,cause_type from ops_event where fingerprint=? order by id desc limit 50",data.get("scope_code")));
  data.put("deliveries",db.queryForList("select id,kind,batch_no,status,attempts,sent_at,last_error from ops_alert_delivery where alert_id=? order by id desc limit 100",id));
  data.put("actions",db.queryForList("select actor_user_id,action_type,request_id,occurred_at from ops_alert_action_audit where alert_id=? order by id desc limit 100",id));return data;
 }
 @Transactional public void acknowledge(long id,long actor,int expectedVersion,String comment){
  Map<String,Object> alert=locked(id);version(alert,expectedVersion);
  if("RESOLVED".equals(alert.get("status")))throw conflict("ALERT_ALREADY_RESOLVED");
  db.update("update ops_alert_instance set status='ACKED',acknowledged_at=now(3),acknowledged_by=?,version_no=version_no+1,time_updated=now(3) where id=?",actor,id);
  audit(id,actor,"ACK",Map.of("comment",safe(comment,250)));
 }
 @Transactional public void silence(long id,long actor,int expectedVersion,Instant until,String reason){
  Map<String,Object> alert=locked(id);version(alert,expectedVersion);
  if("RESOLVED".equals(alert.get("status")))throw conflict("ALERT_ALREADY_RESOLVED");
  if(until==null||until.isBefore(Instant.now())||until.isAfter(Instant.now().plus(Duration.ofHours(24))))throw invalid("Invalid silence time");
  if(reason==null||reason.isBlank())throw invalid("Reason required");
  db.update("update ops_alert_instance set silence_until=?,version_no=version_no+1,time_updated=now(3) where id=?",java.sql.Timestamp.from(until),id);
  audit(id,actor,"SILENCE",Map.of("until",until.toString(),"reason",safe(reason,250)));
 }
 @Transactional public void resolve(long id,long actor,int expectedVersion,String reason){
  Map<String,Object> alert=locked(id);version(alert,expectedVersion);
  if("RESOLVED".equals(alert.get("status")))throw conflict("ALERT_ALREADY_RESOLVED");
  if(reason==null||reason.isBlank())throw invalid("Verification reason required");
  if(List.of("COLLAB_ARTIFACT_PUBLISH_BLOCKED","FEISHU_CRITICAL_OUTBOX_FAILED").contains(alert.get("rule_code")))throw conflict("ALERT_RESOLVE_VERIFICATION_REQUIRED");
  lifecycle.resolveAutomatically(id,safe(reason,250));
  db.update("update ops_alert_instance set version_no=version_no+1 where id=?",id);
  audit(id,actor,"RESOLVE",Map.of("reason",safe(reason,250)));
 }
 public Map<String,Object> config(){Map<String,Object> setting=db.queryForMap("select setting_json,version_no from ops_alert_settings where setting_key='DEFAULT_ALERT_NOTIFICATION'");return Map.of("setting",parse(String.valueOf(setting.get("setting_json"))),"version",setting.get("version_no"),"recipients",recipients());}
 public List<Map<String,Object>> recipients(){return db.queryForList("select r.user_id,r.scope_code,r.min_severity,r.enabled,r.version_no,u.display_name,case when u.status='ACTIVE' and u.feishu_open_id is not null then true else false end feishu_ready from ops_alert_recipient r join iam_user u on u.id=r.user_id order by r.user_id,r.scope_code");}
 public List<Map<String,Object>> candidates(String keyword){String value=keyword==null?"":keyword.trim();if(value.length()>80)throw invalid("Search too long");return db.queryForList("select id,display_name,case when feishu_open_id is not null then true else false end feishu_ready from iam_user where status='ACTIVE' and display_name like ? order by display_name limit 30","%"+value+"%");}
 @Transactional public void setRecipient(long userId,String scope,String severity,boolean enabled,Integer expectedVersion,long actor){admin(actor);if(scope==null||!scope.matches("ALL|[A-Z_]{2,48}"))throw invalid("Invalid scope");if(!List.of("P0","P1").contains(severity))throw invalid("Invalid severity");
  Integer valid=db.queryForObject("select count(*) from iam_user where id=? and status='ACTIVE' and feishu_open_id is not null",Integer.class,userId);if(valid==null||valid==0)throw new BusinessException("ALERT_RECIPIENT_NOT_FEISHU_READY","Recipient is not Feishu ready",HttpStatus.UNPROCESSABLE_ENTITY);
  List<Map<String,Object>> old=db.queryForList("select enabled,version_no,min_severity from ops_alert_recipient where user_id=? and scope_code=? and channel='FEISHU' for update",userId,scope);
  if(old.isEmpty()){if(expectedVersion!=null&&expectedVersion!=0)throw conflict("ALERT_VERSION_CONFLICT");db.update("insert into ops_alert_recipient(user_id,scope_code,min_severity,channel,enabled,configured_by,time_updated) values(?,?,?,'FEISHU',?,?,now(3))",userId,scope,severity,enabled,actor);}
  else{int version=((Number)old.get(0).get("version_no")).intValue();if(expectedVersion==null||expectedVersion!=version)throw conflict("ALERT_VERSION_CONFLICT");db.update("update ops_alert_recipient set enabled=?,min_severity=?,version_no=version_no+1,configured_by=?,time_updated=now(3) where user_id=? and scope_code=? and channel='FEISHU'",enabled,severity,actor,userId,scope);}
  audit(null,actor,"RECIPIENT_CONFIG",Map.of("userId",userId,"scope",scope,"minSeverity",severity,"enabled",enabled));
 }
 @Transactional public void updateConfig(String settings,int expectedVersion,long actor){admin(actor);Map<String,Object> parsed=parse(settings);
  int initial=number(parsed.get("initialMinutes"));int factor=number(parsed.get("factor"));int max=number(parsed.get("maxMinutes"));if(!(parsed.get("enabled") instanceof Boolean)||initial<1||initial>60||factor<1||factor>4||max<initial||max>240)throw invalid("Invalid repeat policy");
  int changed=db.update("update ops_alert_settings set setting_json=?,version_no=version_no+1,updated_by=?,time_updated=now(3) where setting_key='DEFAULT_ALERT_NOTIFICATION' and version_no=?",settings,actor,expectedVersion);if(changed==0)throw conflict("ALERT_VERSION_CONFLICT");audit(null,actor,"SETTINGS_CONFIG",Map.of("version",expectedVersion+1));
 }
 public List<Map<String,Object>> rules(){return db.queryForList("select rule_code,display_name,module,rule_type,severity,enabled,rule_config_json,recovery_config_json,version_no from ops_alert_rule order by module,rule_code");}
 @Transactional public void updateRule(String code,boolean enabled,String ruleConfig,String recoveryConfig,int expectedVersion,long actor){admin(actor);Map<String,Object> candidate=parse(ruleConfig);Map<String,Object> recovery=parse(recoveryConfig);
  Map<String,Object> current=db.queryForMap("select rule_config_json,rule_type from ops_alert_rule where rule_code=?",code);
  Map<String,Object> old=parse(String.valueOf(current.get("rule_config_json")));
  if(!java.util.Objects.equals(candidate.get("eventType"),old.get("eventType")))throw invalid("Event type cannot be changed");
  int window=number(candidate.getOrDefault("windowSeconds",900));if(window<60||window>86400)throw invalid("Invalid window");
  for(String key:List.of("minimumSamples","consecutiveFailures","minimumFailures"))if(candidate.containsKey(key)&&(number(candidate.get(key))<1||number(candidate.get(key))>10000))throw invalid("Invalid threshold");
  if(candidate.containsKey("failureRateGreaterOrEqual")&&(!(candidate.get("failureRateGreaterOrEqual") instanceof Number n)||n.doubleValue()<0||n.doubleValue()>1))throw invalid("Invalid failure rate");
  if(recovery.containsKey("consecutiveSuccesses")&&(number(recovery.get("consecutiveSuccesses"))<1||number(recovery.get("consecutiveSuccesses"))>100))throw invalid("Invalid recovery count");
  if(db.update("update ops_alert_rule set enabled=?,rule_config_json=?,recovery_config_json=?,version_no=version_no+1,updated_by=?,time_updated=now(3) where rule_code=? and version_no=?",enabled,ruleConfig,recoveryConfig,actor,code,expectedVersion)==0)throw conflict("ALERT_VERSION_CONFLICT");audit(null,actor,"RULE_CONFIG",Map.of("ruleCode",code,"enabled",enabled));}
 public void test(List<Long> users,long actor){admin(actor);if(users==null||users.isEmpty()||users.size()>5)throw invalid("Select 1..5 recipients");Integer recent=db.queryForObject("select count(*) from ops_alert_action_audit where actor_user_id=? and action_type='TEST_NOTIFICATION' and occurred_at>date_sub(now(3),interval 1 minute)",Integer.class,actor);if(recent!=null&&recent>0)throw new BusinessException("ALERT_TEST_RATE_LIMITED","Please wait before another test",HttpStatus.TOO_MANY_REQUESTS);
  for(long user:users){List<String> ids=db.query("select feishu_open_id from iam_user where id=? and status='ACTIVE' and feishu_open_id is not null",(r,n)->r.getString(1),user);if(ids.isEmpty())throw invalid("Recipient not ready");feishu.sendInteractiveToUser(ids.get(0),CollabFeishuCardLayout.card("研枢告警通道测试","blue",List.of(CollabFeishuCardLayout.markdown("这是告警通道测试卡片，不代表业务故障。"))),"ops-test:"+UUID.randomUUID());}audit(null,actor,"TEST_NOTIFICATION",Map.of("recipientCount",users.size()));
 }
 private Map<String,Object> locked(long id){List<Map<String,Object>> result=db.queryForList("select id,status,version_no,rule_code from ops_alert_instance where id=? for update",id);if(result.isEmpty())throw missing();return result.get(0);}
 private void version(Map<String,Object> alert,int expected){if(((Number)alert.get("version_no")).intValue()!=expected)throw conflict("ALERT_VERSION_CONFLICT");}
 public void requireAdmin(long actor){admin(actor);}
 private void admin(long actor){Integer n=db.queryForObject("select count(*) from iam_user_role ur join iam_role r on r.id=ur.role_id where ur.user_id=? and r.role_key='ADMIN' and r.status='ACTIVE'",Integer.class,actor);if(n==null||n==0)throw new BusinessException("ALERT_OPERATION_FORBIDDEN","Super administrator required",HttpStatus.FORBIDDEN);}
 private void audit(Long alertId,long actor,String action,Map<String,Object> after){try{db.update("insert into ops_alert_action_audit(alert_id,actor_user_id,action_type,request_id,after_json,occurred_at) values(?,?,?,?,?,now(3))",alertId,actor,action,LogContext.requestId(),json.writeValueAsString(after));}catch(Exception ex){throw new IllegalStateException("Failed to audit alert operation",ex);}}
 private Map<String,Object> parse(String text){try{return json.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<>(){});}catch(Exception ex){throw invalid("Invalid JSON configuration");}}
 private int number(Object value){return value instanceof Number n?n.intValue():0;}
 private String safe(String value,int max){if(value==null)return "";String v=value.replaceAll("[\\x00-\\x1f]"," ");return v.substring(0,Math.min(v.length(),max));}
 private BusinessException invalid(String message){return new BusinessException("ALERT_CONFIG_INVALID",message,HttpStatus.BAD_REQUEST);}
 private BusinessException conflict(String code){return new BusinessException(code,code,HttpStatus.CONFLICT);}
 private BusinessException missing(){return new BusinessException("ALERT_NOT_FOUND","Alert not found",HttpStatus.NOT_FOUND);}
}
