package com.company.skillplatform.ops;

import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkerProperties;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ComponentHealthProbeWorker {
 private static final Logger log=LoggerFactory.getLogger(ComponentHealthProbeWorker.class);
 private final JdbcTemplate db;private final OpsEventService events;private final RestClient client;private final List<Map.Entry<String,String>> targets;
 public ComponentHealthProbeWorker(JdbcTemplate db,OpsEventService events,RestClient.Builder builder,CodeGraphWorkerProperties codeGraph,KnowledgeProperties knowledge,
  @Value("${agent.runtime.base-url:http://127.0.0.1:3090}") String runtime,
  @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:18080}") String gateway){
  this.db=db;this.events=events;var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());factory.setReadTimeout(Duration.ofSeconds(3));client=builder.clone().requestFactory(factory).build();
  targets=List.of(Map.entry("agentRuntime",trim(runtime)+"/healthz"),Map.entry("gateway",trim(gateway)+"/alive"),Map.entry("codeGraphWorker",trim(codeGraph.baseUrl())+"/internal/code-graph/health"),Map.entry("qdrant",trim(knowledge.getQdrant().getBaseUrl())+"/collections"));
 }
 @Scheduled(initialDelayString="${skill-platform.observability.health-probe-delay-ms:10000}",fixedDelayString="${skill-platform.observability.health-probe-delay-ms:30000}")
 public void sample(){for(var target:targets){long started=System.nanoTime();String status="HEALTHY",code=null;try{client.get().uri(target.getValue()).retrieve().toBodilessEntity();}catch(Exception ex){status="UNAVAILABLE";code=ex.getClass().getSimpleName();}
  try{db.update("insert into ops_probe_snapshot(component_key,checked_at,status,latency_ms,error_code) values(?,now(3),?,?,?)",target.getKey(),status,(System.nanoTime()-started)/1_000_000,code);
   if("agentRuntime".equals(target.getKey()))events.record(new OpsEventService.Event("dsh-probe:"+(System.currentTimeMillis()/30000),"agent.dsh.probe","DSH_mcp","probe","HEALTHY".equals(status)?"SUCCESS":"FAILED","GLOBAL",code,null,null,null,null,(System.nanoTime()-started)/1_000_000,null));}catch(Exception ex){log.error("[Yanshu][ALERT] event=ops.probe.write_failed component={}",target.getKey(),ex);}
 }}
 private String trim(String value){return value==null?"":value.replaceAll("/$","");}
}
