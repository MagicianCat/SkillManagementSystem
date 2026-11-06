package com.company.skillplatform.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OpsRetentionWorker {
 private static final Logger log=LoggerFactory.getLogger(OpsRetentionWorker.class);
 private final JdbcTemplate db;
 public OpsRetentionWorker(JdbcTemplate db){this.db=db;}
 @Scheduled(cron="${skill-platform.observability.retention-cron:0 40 3 * * *}")
 public void purge(){
  try{
   db.update("delete from ops_event where occurred_at<date_sub(now(3),interval 90 day) and id not in(select latest_event_id from ops_alert_instance where latest_event_id is not null) limit 1000");
   db.update("delete from ops_metric_bucket where bucket_start<date_sub(now(3),interval 12 month) limit 1000");
   db.update("delete from ops_probe_snapshot where checked_at<date_sub(now(3),interval 30 day) limit 1000");
  }catch(Exception ex){log.error("[Yanshu][ALERT] event=ops.retention.failed",ex);}
 }
}
