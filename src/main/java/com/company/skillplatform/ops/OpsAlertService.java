package com.company.skillplatform.ops;

import com.company.skillplatform.collabproject.application.CollabFeishuCardLayout;
import com.company.skillplatform.collabproject.application.CollabFeishuChatGateway;
import com.company.skillplatform.common.logging.LogContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsAlertService {
 private static final Logger log=LoggerFactory.getLogger(OpsAlertService.class);
 private final JdbcTemplate db;
 private final CollabFeishuChatGateway feishu;
 private final OpsEventService events;private final FeishuAlertCardFactory cards;
 @org.springframework.beans.factory.annotation.Autowired
 public OpsAlertService(JdbcTemplate db,CollabFeishuChatGateway feishu,OpsEventService events,FeishuAlertCardFactory cards){this.db=db;this.feishu=feishu;this.events=events;this.cards=cards;}
 public void dshEvent(String runKey,String phase,Throwable error){
  String eventType=phase.startsWith("create")?"agent.dsh.create":phase.startsWith("events")?"agent.dsh.stream":"agent.dsh.request";
  String code=error==null?null:com.company.skillplatform.common.application.ErrorClassifier.classify(error,"DSH_RUNTIME_ERROR").code();
  events.record(new OpsEventService.Event(null,eventType,"DSH_mcp",phase,error==null?"SUCCESS":"FAILED","GLOBAL",code,runKey,null,null,null,null,null,phase,error==null?null:com.company.skillplatform.common.application.ErrorClassifier.classify(error,code).rootCauseType()));
  if(error!=null&&phase.contains("stream")&&(code.equals("UPSTREAM_TIMEOUT")||code.equals("DSH_RUNTIME_ERROR")))
   events.record(new OpsEventService.Event(null,"agent.dsh.stream_timeout","DSH_mcp",phase,"FAILED","GLOBAL",code,runKey,null,null,null,null,null));
 }
 public void enqueueBatch(long alertId,String kind,int batch){
  Boolean globallyEnabled=db.queryForObject("select json_extract(setting_json,'$.enabled')=true from ops_alert_settings where setting_key='DEFAULT_ALERT_NOTIFICATION'",Boolean.class);if(!Boolean.TRUE.equals(globallyEnabled))return;
  Boolean enabled=db.queryForObject("select notification_enabled from ops_alert_instance where id=?",Boolean.class,alertId);if(!Boolean.TRUE.equals(enabled))return;
  db.update("insert ignore into ops_alert_delivery(alert_id,recipient_user_id,kind,batch_no,idempotency_key,status,next_attempt_at,created_at) select ?,r.user_id,?,?,concat('ops:',?,':',r.user_id,':',?,':',?),'PENDING',now(3),now(3) from ops_alert_recipient r join ops_alert_instance a on a.id=? where r.enabled=true and (r.scope_code='ALL' or r.scope_code=a.module) and (a.severity='P0' or r.min_severity='P1')",alertId,kind,batch,alertId,kind,batch,alertId);
 }
 public void resolveAutomatically(long id,String reason){
  if(db.update("update ops_alert_instance set status='RESOLVED',active_fingerprint=null,resolved_at=now(3),resolution_reason=?,next_reminder_at=null,time_updated=now(3) where id=? and active_fingerprint is not null",reason,id)==0)return;
  db.update("update ops_alert_delivery set status='CANCELLED' where alert_id=? and kind='REMINDER' and status in ('PENDING','SENDING')",id);
  enqueueBatch(id,"RESOLVED",0);
 }
 @Scheduled(fixedDelayString="${skill-platform.alerting.reminder-delay-ms:30000}")
 @Transactional
 public void reminders(){
  for(Map<String,Object> row:db.queryForList("select id,reminder_count from ops_alert_instance where active_fingerprint is not null and (silence_until is null or silence_until<=now(3)) and next_reminder_at<=now(3) limit 50")){
   long id=((Number)row.get("id")).longValue();int count=((Number)row.get("reminder_count")).intValue()+1;
   Map<String,Object> policy=db.queryForMap("select cast(json_unquote(json_extract(setting_json,'$.initialMinutes')) as unsigned) initial,cast(json_unquote(json_extract(setting_json,'$.factor')) as unsigned) factor,cast(json_unquote(json_extract(setting_json,'$.maxMinutes')) as unsigned) maximum from ops_alert_settings where setting_key='DEFAULT_ALERT_NOTIFICATION'");
   int minutes=AlertReminderPolicy.delayMinutes(((Number)policy.get("initial")).intValue(),((Number)policy.get("factor")).intValue(),((Number)policy.get("maximum")).intValue(),count);
   if(db.update("update ops_alert_instance set reminder_count=?,next_reminder_at=date_add(now(3),interval ? minute) where id=? and reminder_count=? and active_fingerprint is not null and (silence_until is null or silence_until<=now(3))",count,minutes,id,count-1)>0)enqueueBatch(id,"REMINDER",count);
  }
 }
 @Scheduled(fixedDelayString="${skill-platform.alerting.delivery-delay-ms:10000}")
 public void deliver(){
  db.update("update ops_alert_delivery d join iam_user u on u.id=d.recipient_user_id set d.status='CANCELLED',d.lease_until=null,d.last_error='Recipient inactive or Feishu unavailable' where d.status in ('PENDING','SENDING') and (u.status<>'ACTIVE' or u.feishu_open_id is null)");
  for(Map<String,Object> item:db.queryForList("select d.id,d.alert_id,d.recipient_user_id,d.kind,d.batch_no,d.attempts,d.idempotency_key,a.title,a.status,a.severity,u.feishu_open_id from ops_alert_delivery d join ops_alert_instance a on a.id=d.alert_id join iam_user u on u.id=d.recipient_user_id where u.status='ACTIVE' and d.status in ('PENDING','SENDING') and d.next_attempt_at<=now(3) and (d.lease_until is null or d.lease_until<now(3)) order by d.id limit 20")){
   long id=((Number)item.get("id")).longValue();
   if(db.update("update ops_alert_delivery set status='SENDING',lease_until=date_add(now(3),interval 40 second),attempts=attempts+1 where id=? and status in ('PENDING','SENDING') and (lease_until is null or lease_until<now(3))",id)==0)continue;
   try{
    String openId=(String)item.get("feishu_open_id");if(openId==null||openId.isBlank())throw new IllegalStateException("Recipient has no Feishu Open ID");
    Integer recipientEnabled=db.queryForObject("select count(*) from ops_alert_recipient where user_id=? and enabled=true",Integer.class,item.get("recipient_user_id"));
    if(recipientEnabled==null||recipientEnabled==0){db.update("update ops_alert_delivery set status='CANCELLED',lease_until=null where id=?",id);continue;}
    Map<String,Object> settings=db.queryForMap("select json_unquote(json_extract(setting_json,'$.enabled')) enabled,cast(json_unquote(json_extract(setting_json,'$.maxMessagesPerRecipientHour')) as unsigned) hourly_limit from ops_alert_settings where setting_key='DEFAULT_ALERT_NOTIFICATION'");
    if(!"true".equals(settings.get("enabled"))){db.update("update ops_alert_delivery set status='SUPPRESSED',lease_until=null,last_error='Notifications disabled' where id=?",id);continue;}
    java.sql.Timestamp silence=db.queryForObject("select silence_until from ops_alert_instance where id=?",java.sql.Timestamp.class,item.get("alert_id"));
    if(!"RESOLVED".equals(item.get("kind"))&&silence!=null&&silence.toInstant().isAfter(Instant.now())){db.update("update ops_alert_delivery set status='PENDING',next_attempt_at=?,lease_until=null where id=?",silence,id);continue;}
    Integer sentLastHour=db.queryForObject("select count(*) from ops_alert_delivery where recipient_user_id=? and status='SENT' and sent_at>date_sub(now(3),interval 1 hour)",Integer.class,item.get("recipient_user_id"));
    int hourlyLimit=settings.get("hourly_limit") instanceof Number limit?limit.intValue():20;
    if(sentLastHour!=null&&sentLastHour>=hourlyLimit){db.update("update ops_alert_delivery set status='SUPPRESSED',lease_until=null,last_error='Hourly recipient limit' where id=?",id);continue;}
    String kind=(String)item.get("kind");
    String currentState=db.queryForObject("select status from ops_alert_instance where id=?",String.class,item.get("alert_id"));
    if("REMINDER".equals(kind)&&"RESOLVED".equals(currentState)){db.update("update ops_alert_delivery set status='CANCELLED',lease_until=null where id=?",id);continue;}
    feishu.sendInteractiveToUser(openId,cards.card(((Number)item.get("alert_id")).longValue(),kind),item.get("idempotency_key")==null?"ops:"+id:String.valueOf(item.get("idempotency_key")));
    db.update("update ops_alert_delivery set status='SENT',sent_at=now(3),lease_until=null where id=?",id);
   }catch(Exception ex){int attempts=((Number)item.get("attempts")).intValue()+1;db.update("update ops_alert_delivery set status=?,next_attempt_at=date_add(now(3),interval ? second),lease_until=null,last_error=? where id=?",attempts>=5?"FAILED":hasTimeout(ex)?"UNKNOWN":"PENDING",Math.min(300,10*(1<<Math.min(attempts,4))),ex.getClass().getSimpleName(),id);log.warn("[Yanshu][ALERT] event=alert.notification.failed deliveryId={} causeType={}",id,ex.getClass().getSimpleName(),ex);if(attempts>=5)events.recordFailure(new OpsEventService.Event("alert-delivery:"+id+":failed","alert.delivery","ALERT","deliver","FAILED","delivery:"+id,"ALERT_DELIVERY_FAILED",null,null,null,null,null,null));}
  }
 }
 private boolean hasTimeout(Throwable error){for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof java.net.http.HttpTimeoutException||cause instanceof java.net.SocketTimeoutException)return true;return false;}
}
