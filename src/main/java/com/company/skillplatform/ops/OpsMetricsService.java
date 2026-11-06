package com.company.skillplatform.ops;

import java.time.Duration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OpsMetricsService {
 private static final Logger log=LoggerFactory.getLogger(OpsMetricsService.class);
 private final JdbcTemplate db;private final OpsEventService events;private final ObjectMapper json;private final TransactionTemplate tx;
 public OpsMetricsService(JdbcTemplate db,OpsEventService events,ObjectMapper json,TransactionTemplate tx){this.db=db;this.events=events;this.json=json;this.tx=tx;}
 @Scheduled(fixedDelayString="${skill-platform.observability.metrics-aggregate-delay-ms:60000}")
 public void aggregate(){
  try{tx.executeWithoutResult(status->aggregateTransactional());}
  catch(RuntimeException ex){log.error("[Yanshu][ALERT] event=ops.metrics.aggregate_failed",ex);events.recordFailure(new OpsEventService.Event(null,"ops.data.read","ALERT","metrics_aggregate","FAILED","metrics","OPS_METRICS_AGGREGATE_FAILED",null,null,null,null,null,null));}
 }
 private void aggregateTransactional(){
  String owner=java.util.UUID.randomUUID().toString();
  if(db.update("update ops_worker_lease set lease_until=date_add(now(3),interval 180 second),owner_id=?,time_updated=now(3) where worker_key='metrics_aggregate' and lease_until<now(3)",owner)==0)return;
  try{
   db.update("delete from ops_metric_bucket where bucket_start>=cast(date_format(date_sub(now(3),interval 120 minute),'%Y-%m-%d %H:%i:00') as datetime) and bucket_minutes=1");
   db.update("""
    insert into ops_metric_bucket(bucket_start,bucket_minutes,module,operation,scope_code,sample_count,success_count,failure_count,degraded_count,duration_sum_ms,duration_histogram_json,calculated_at)
    select cast(date_format(occurred_at,'%Y-%m-%d %H:%i:00') as datetime),1,module,coalesce(operation,'unknown'),'GLOBAL',
     count(*),sum(outcome='SUCCESS'),sum(outcome='FAILED'),sum(outcome='DEGRADED'),sum(coalesce(duration_ms,0)),
     json_object('50',sum(duration_ms<=50),'100',sum(duration_ms>50 and duration_ms<=100),'250',sum(duration_ms>100 and duration_ms<=250),
      '500',sum(duration_ms>250 and duration_ms<=500),'1000',sum(duration_ms>500 and duration_ms<=1000),
      '2000',sum(duration_ms>1000 and duration_ms<=2000),'5000',sum(duration_ms>2000 and duration_ms<=5000),
      '10000',sum(duration_ms>5000 and duration_ms<=10000),'overflow',sum(duration_ms>10000)),now(3)
    from ops_event where occurred_at>=date_sub(now(3),interval 120 minute) group by cast(date_format(occurred_at,'%Y-%m-%d %H:%i:00') as datetime),module,coalesce(operation,'unknown')
   """);
  }finally{db.update("update ops_worker_lease set lease_until='1970-01-01 00:00:00',owner_id=null,time_updated=now(3) where worker_key='metrics_aggregate' and owner_id=?",owner);}
 }
 public Map<String,Object> metrics(String module,Duration window){
  if(module==null||!module.matches("[A-Za-z0-9_]{1,48}"))throw new IllegalArgumentException("Invalid module");
  long minutes=Math.min(1440,Math.max(1,window.toMinutes()));
  List<Map<String,Object>> series=db.queryForList("select bucket_start as at,sum(sample_count) requests,sum(success_count) success,sum(failure_count) failed,sum(degraded_count) degraded,sum(duration_sum_ms) duration_ms from ops_metric_bucket where module=? and bucket_start>=date_sub(now(3),interval ? minute) group by bucket_start order by bucket_start",module,minutes);
  List<Map<String,Object>> histograms=db.queryForList("select duration_histogram_json from ops_metric_bucket where module=? and bucket_start>=date_sub(now(3),interval ? minute)",module,minutes);
  long[] merged=new long[9];for(var histogram:histograms)merge(merged,histogram.get("duration_histogram_json"));
  long samples=series.stream().mapToLong(x->number(x.get("requests"))).sum();long failures=series.stream().mapToLong(x->number(x.get("failed"))).sum();
  long success=series.stream().mapToLong(x->number(x.get("success"))).sum();long degraded=series.stream().mapToLong(x->number(x.get("degraded"))).sum();
  Map<String,Object> summary=new LinkedHashMap<>();summary.put("requests",samples);summary.put("success",success);summary.put("failed",failures);summary.put("degraded",degraded);summary.put("failureRate",samples==0?null:failures/(double)samples);summary.put("degradationRate",samples==0?null:degraded/(double)samples);summary.put("p95Ms",percentile(merged,0.95));summary.put("p99Ms",percentile(merged,0.99));
  return Map.of("module",module,"window",window.toString(),"granularity","PT1M","dataStatus",samples==0?"NO_DATA":"OK","summary",summary,"series",series,
   "errorGroups",db.queryForList("select error_code,count(*) as count from ops_event where module=? and error_code is not null and occurred_at>=date_sub(now(3),interval ? minute) group by error_code order by count desc limit 10",module,minutes));
 }
 private void merge(long[] target,Object raw){try{JsonNode node=json.readTree(String.valueOf(raw));String[] keys={"50","100","250","500","1000","2000","5000","10000","overflow"};for(int i=0;i<keys.length;i++)target[i]+=node.path(keys[i]).asLong();}catch(Exception ignored){}}
 private Long percentile(long[] bins,double p){long total=0;for(long count:bins)total+=count;if(total==0)return null;long target=(long)Math.ceil(total*p),sum=0;long[] bounds={50,100,250,500,1000,2000,5000,10000,20000};for(int i=0;i<bins.length;i++){sum+=bins[i];if(sum>=target)return bounds[i];}return bounds[bounds.length-1];}
 private long number(Object value){return value instanceof Number n?n.longValue():0;}
}
