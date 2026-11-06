package com.company.skillplatform.ops;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Assesses only generations whose late-upload grace period has elapsed. */
@Service
public class TelemetryQualityAssessmentService {
 private static final Logger log=LoggerFactory.getLogger(TelemetryQualityAssessmentService.class);
 private final JdbcTemplate db;private final OpsEventService events;
 public TelemetryQualityAssessmentService(JdbcTemplate db,OpsEventService events){this.db=db;this.events=events;}
 @Scheduled(fixedDelayString="${skill-platform.observability.quality-delay-ms:60000}")
 public void assess(){
  try{
   List<Map<String,Object>> rows=db.queryForList("select g.id,g.user_id,g.client_installation_id,g.started_at,g.capture_revision,g.capture_quality,g.model_call_count,g.total_tokens,g.token_mismatch,g.time_updated,q.capture_revision as assessed_revision from ai_generation_event g left join ops_telemetry_quality q on q.generation_event_id=g.id where g.time_updated>date_sub(now(3),interval 2 day) and g.time_updated<date_sub(now(3),interval 10 minute) and (q.generation_event_id is null or q.capture_revision<g.capture_revision) order by g.time_updated limit 500");
   for(Map<String,Object> row:rows)assessOne(row);
  }catch(Exception ex){log.error("[Yanshu][TELEMETRY] event=telemetry.quality.assessment_failed",ex);}
 }
 void assessOne(Map<String,Object> row){
  long id=((Number)row.get("id")).longValue();long user=((Number)row.get("user_id")).longValue();long revision=((Number)row.get("capture_revision")).longValue();
  String installation=row.get("client_installation_id")==null?"UNKNOWN":String.valueOf(row.get("client_installation_id"));
  int modelCalls=row.get("model_call_count") instanceof Number n?n.intValue():0;
  long total=row.get("total_tokens") instanceof Number n?n.longValue():0;
  boolean mismatch=Boolean.TRUE.equals(row.get("token_mismatch"))||row.get("token_mismatch") instanceof Number n&&n.intValue()!=0;
  String grade=modelCalls>0&&total<=0?"SEVERE_INCOMPLETE":mismatch?"PARTIAL":"COMPLETE";
  db.update("insert into ops_telemetry_quality(generation_event_id,actor_user_id,client_installation_id,protocol_version,generation_started_at,capture_revision,quality_grade,missing_fields_json,expected_capabilities_json,grace_until,assessed_at) values(?,?,?,'UNKNOWN',?,?,?,?,?,date_add(?,interval 10 minute),now(3)) on duplicate key update capture_revision=values(capture_revision),quality_grade=values(quality_grade),missing_fields_json=values(missing_fields_json),grace_until=values(grace_until),assessed_at=now(3)",
   id,user,installation,row.get("started_at"),revision,grade,grade.equals("SEVERE_INCOMPLETE")?"[\"totalTokens\"]":"[]","[]",row.get("time_updated"));
  String scope=user+":"+installation;
  events.record(new OpsEventService.Event("telemetry-quality:"+id+":revision:"+revision,"telemetry.generation.quality","TELEMETRY","quality",grade.equals("SEVERE_INCOMPLETE")?"FAILED":"SUCCESS",scope,grade.equals("SEVERE_INCOMPLETE")?"GENERATION_TOKENS_MISSING":null,null,null,null,null,null,null));
 }
}
