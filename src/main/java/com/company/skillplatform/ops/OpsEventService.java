package com.company.skillplatform.ops;

import com.company.skillplatform.common.logging.LogContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists safe, low-cardinality business facts. No prompt or provider body is accepted. */
@Service
public class OpsEventService {
 private static final Logger log=LoggerFactory.getLogger(OpsEventService.class);
 private final JdbcTemplate db; private final MeterRegistry meters;
 public OpsEventService(JdbcTemplate db,MeterRegistry meters){this.db=db;this.meters=meters;}
 public record Event(String eventKey,String eventType,String module,String operation,String outcome,String scope,
                     String errorCode,String runKey,String projectKey,String requirementKey,Long stageId,
                     Long durationMs,Instant occurredAt,String phase,String causeType) {
  public Event(String eventKey,String eventType,String module,String operation,String outcome,String scope,String errorCode,String runKey,String projectKey,String requirementKey,Long stageId,Long durationMs,Instant occurredAt){this(eventKey,eventType,module,operation,outcome,scope,errorCode,runKey,projectKey,requirementKey,stageId,durationMs,occurredAt,null,null);}
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public boolean recordFailure(Event event){return write(event);}
 @Transactional
 public boolean record(Event event){return write(event);}
 private boolean write(Event event){
  if(!event.eventType().matches("[a-z0-9_.]{1,128}")||!event.module().matches("[A-Za-z0-9_]{1,48}")
   ||!event.outcome().matches("SUCCESS|FAILED|DEGRADED|BLOCKED|CANCELLED|UNKNOWN"))throw new IllegalArgumentException("Invalid observability event");
  String scope=event.scope()==null||event.scope().isBlank()?"GLOBAL":safe(event.scope(),180);
  String requestId=LogContext.requestId();String traceId=MDC.get("traceId");
  if(traceId==null||traceId.isBlank())traceId="-".equals(requestId)?UUID.randomUUID().toString():requestId;
  String eventKey=event.eventKey()==null?UUID.randomUUID().toString():safe(event.eventKey(),180);
  try{
   db.update("insert into ops_event(event_key,event_type,module,operation,outcome,fingerprint,run_key,project_key,requirement_key,stage_id,request_id,trace_id,error_code,duration_ms,phase,cause_type,occurred_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
    eventKey,event.eventType(),event.module(),safe(event.operation(),80),event.outcome(),scope,safe(event.runKey(),120),safe(event.projectKey(),128),safe(event.requirementKey(),128),event.stageId(),requestId,traceId,safe(event.errorCode(),80),event.durationMs(),safe(event.phase(),60),safe(event.causeType(),100),java.sql.Timestamp.from(event.occurredAt()==null?Instant.now():event.occurredAt()));
   meters.counter("yanshu_observability_event_total","module",event.module(),"outcome",event.outcome()).increment();
   return true;
  }catch(DuplicateKeyException duplicate){return false;}
  catch(RuntimeException ex){meters.counter("yanshu_observability_event_write_failed_total","module",event.module()).increment();log.error("[Yanshu][ALERT] event=ops.event.write_failed traceId={} module={} eventType={}",traceId,event.module(),event.eventType(),ex);return false;}
 }
 public static String fingerprint(String environment,String ruleCode,String scope,String rootCause){
  try{byte[] digest=MessageDigest.getInstance("SHA-256").digest((environment+"|"+ruleCode+"|"+scope+"|"+rootCause).getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(digest);}
  catch(Exception ex){throw new IllegalStateException(ex);}
 }
 private static String safe(String value,int max){if(value==null)return null;String clean=value.replaceAll("[\\x00-\\x1f]"," ");return clean.substring(0,Math.min(max,clean.length()));}
}
