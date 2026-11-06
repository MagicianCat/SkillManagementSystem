package com.company.skillplatform.ops;

import com.company.skillplatform.collabproject.application.CollabFeishuCardLayout;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FeishuAlertCardFactory {
 private final JdbcTemplate db;private final String baseUrl;
 public FeishuAlertCardFactory(JdbcTemplate db,@Value("${skill-platform.alerting.web-base-url:${YANSHU_WEB_BASE_URL:}}") String baseUrl){this.db=db;this.baseUrl=trusted(baseUrl);}
 public Map<String,Object> card(long alertId,String kind){
  Map<String,Object> alert=db.queryForMap("select a.alert_no,a.title,a.module,a.severity,a.status,a.summary,a.error_code,a.first_seen_at,a.last_seen_at,a.resolved_at,a.resolution_reason,e.trace_id,e.run_key,e.cause_type from ops_alert_instance a join ops_event e on e.id=a.latest_event_id where a.id=?",alertId);
  String title="研枢 "+alert.get("severity")+" · "+("RESOLVED".equals(kind)?"告警恢复":"告警"+kind);
  StringBuilder body=new StringBuilder();
  body.append("**").append(escape(String.valueOf(alert.get("title")))).append("**\n");
  line(body,"告警编号",alert.get("alert_no"));line(body,"模块",alert.get("module"));line(body,"状态",alert.get("status"));line(body,"错误码",alert.get("error_code"));line(body,"根因类型",alert.get("cause_type"));line(body,"Trace ID",alert.get("trace_id"));line(body,"Run",alert.get("run_key"));line(body,"首次发生",alert.get("first_seen_at"));
  if("RESOLVED".equals(kind)){line(body,"恢复时间",alert.get("resolved_at"));line(body,"恢复依据",alert.get("resolution_reason"));}
  else line(body,"最近发生",alert.get("last_seen_at"));
  List<Object> elements=new ArrayList<>();elements.add(CollabFeishuCardLayout.markdown(body.toString()));
  if(!baseUrl.isBlank())elements.add(CollabFeishuCardLayout.markdown("[查看告警详情]("+baseUrl+"/agent/mcp-audits?tab=alerts&alertId="+alertId+")"));
  return CollabFeishuCardLayout.card(title,"RESOLVED".equals(kind)?"green":"P0".equals(alert.get("severity"))?"red":"orange",elements);
 }
 private void line(StringBuilder body,String label,Object value){if(value!=null)body.append(label).append("：").append(escape(String.valueOf(value))).append("\n");}
 private String escape(String value){return CollabFeishuCardLayout.escape(value.replaceAll("[\\x00-\\x1f]"," "));}
 private String trusted(String value){if(value==null||value.isBlank())return "";try{URI uri=URI.create(value);if(("https".equals(uri.getScheme())||"http".equals(uri.getScheme())&&"localhost".equals(uri.getHost()))&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null)return value.replaceAll("/$","");}catch(Exception ignored){}return "";}
}
