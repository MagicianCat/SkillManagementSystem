package com.company.skillplatform.ops;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Derives terminal observability facts from durable business state. */
@Service
public class BusinessStateObservationWorker {
 private static final Logger log=LoggerFactory.getLogger(BusinessStateObservationWorker.class);
 private final JdbcTemplate db;private final OpsEventService events;
 public BusinessStateObservationWorker(JdbcTemplate db,OpsEventService events){this.db=db;this.events=events;}
 @Scheduled(fixedDelayString="${skill-platform.observability.business-state-delay-ms:60000}")
 public void observe(){
  try{outbox();syncJobs();stalledStages();indexBacklog();}catch(Exception ex){log.error("[Yanshu][ALERT] event=ops.business_state.read_failed",ex);}
 }
 void outbox(){
  List<Map<String,Object>> rows=db.queryForList("select o.id,o.project_id,o.event_type,o.aggregate_id,o.status,o.attempts,p.project_key from collab_outbox o join collab_project p on p.id=o.project_id where o.time_updated>date_sub(now(3),interval 2 day) and o.status in('FAILED','RECONCILE_REQUIRED','DONE') order by o.time_updated desc limit 500");
  for(var row:rows){String type=String.valueOf(row.get("event_type"));if(!List.of("PUBLISH_ARTIFACT","CREATE_CHAT","CREATE_WIKI_FOLDER","ASDM_CONFIRM_PUBLISH").contains(type))continue;
   String status=String.valueOf(row.get("status"));String eventType="PUBLISH_ARTIFACT".equals(type)?"collab.artifact.publish":"collab.feishu.outbox";
   long id=((Number)row.get("id")).longValue();String outcome="DONE".equals(status)?"SUCCESS":"BLOCKED";
   String code="DONE".equals(status)?null:"RECONCILE_REQUIRED".equals(status)?"FEISHU_RECONCILE_REQUIRED":"FEISHU_OUTBOX_FAILED";
   events.record(new OpsEventService.Event("collab-outbox:"+id+":"+status+":"+row.get("attempts"),eventType,"PUBLISH_ARTIFACT".equals(type)?"ARTIFACT_PUBLISH":"FEISHU",type,outcome,"outbox:"+id,code,null,String.valueOf(row.get("project_key")),String.valueOf(row.get("project_key")),null,null,null));
  }
 }
 void indexBacklog(){
  Integer count=db.queryForObject("select count(*) from ops_index_state where status in('PENDING','EXHAUSTED') and time_updated<date_sub(now(3),interval 15 minute)",Integer.class);
  if(count==null)return;
  String outcome=count>0?"FAILED":"SUCCESS";
  events.record(new OpsEventService.Event("wiki-index-backlog:"+(System.currentTimeMillis()/60000),"knowledge.index.progress","WIKI_INDEX","index",outcome,"GLOBAL",count>0?"WIKI_INDEX_BACKLOG":null,null,null,null,null,null,null));
 }
 void stalledStages(){
  List<Map<String,Object>> rows=db.queryForList("select s.id,s.status,s.version_no,p.project_key,coalesce((select max(e.created_at) from collab_stage_event e where e.stage_id=s.id and e.attempt_no=s.attempt_no and e.to_status is not null),s.started_at,s.time_created) progress_at from collab_stage s join collab_project p on p.id=s.project_id where (s.status='PUBLISHING' and s.started_at<date_sub(now(3),interval 25 minute)) or (s.status<>'PUBLISHING' and s.time_updated>date_sub(now(3),interval 2 hour)) order by s.time_updated desc limit 200");
  for(var row:rows){long id=((Number)row.get("id")).longValue();String status=String.valueOf(row.get("status"));String key=String.valueOf(row.get("project_key"));
   if("PUBLISHING".equals(status)){java.sql.Timestamp progress=(java.sql.Timestamp)row.get("progress_at");long stalled=progress==null?0:java.time.Duration.between(progress.toInstant(),java.time.Instant.now()).toMinutes();if(stalled<30)continue;
    events.record(new OpsEventService.Event("collab-stage:"+id+":stall:"+(stalled/5),"collab.stage.progress","COLLAB_FLOW","auto_publish","FAILED","stage:"+id,"COLLAB_AUTO_STAGE_STALLED",null,key,key,id,stalled*60000,null));
   }else events.record(new OpsEventService.Event("collab-stage:"+id+":state:"+row.get("version_no"),"collab.stage.progress","COLLAB_FLOW","auto_publish","SUCCESS","stage:"+id,null,null,key,key,id,null,null));
  }
 }
 void syncJobs(){
  List<Map<String,Object>> rows=db.queryForList("select j.id,j.requirement_id,j.status,j.last_error_code,p.project_key from collab_asdm_sync_job j join collab_project p on p.id=j.requirement_id where j.time_updated>date_sub(now(3),interval 2 day) and j.status in('FAILED','PARTIAL_FAILED','COMPLETED') order by j.time_updated desc limit 500");
  for(var row:rows){String status=String.valueOf(row.get("status"));long id=((Number)row.get("id")).longValue();String key=String.valueOf(row.get("project_key"));
   events.record(new OpsEventService.Event("asdm-sync:"+id+":"+status,"asdm.sync.final","ASDM_GITLAB","sync","COMPLETED".equals(status)?"SUCCESS":"FAILED","sync-job:"+id,"COMPLETED".equals(status)?null:"ASDM_SYNC_FINAL_FAILED",null,key,key,null,null,null));
  }
 }
}
