package com.company.skillplatform.agent.application;

import com.company.skillplatform.codegraph.infrastructure.CodeGraphWorkerProperties;
import com.company.skillplatform.knowledge.infrastructure.KnowledgeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

/** Aggregates the operational view used by the admin dashboard. External failures are isolated per component. */
@Service
public class AgentOperationsDashboardService {
    private final RestClient client;
    private final JdbcTemplate jdbc;
    private final CodeGraphWorkerProperties worker;
    private final KnowledgeProperties knowledge;
    private final String runtimeUrl;
    private final String gatewayUrl;
    private final ThreadPoolExecutor agentExecutor;


    public AgentOperationsDashboardService(RestClient.Builder builder, JdbcTemplate jdbc,
            CodeGraphWorkerProperties worker, KnowledgeProperties knowledge, ThreadPoolExecutor agentRuntimeExecutor,
            @Value("${agent.runtime.base-url:http://127.0.0.1:3090}") String runtimeUrl,
            @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:18080}") String gatewayUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.clone().requestFactory(factory).build(); this.jdbc = jdbc; this.worker = worker;
        this.knowledge = knowledge; this.runtimeUrl = trim(runtimeUrl); this.gatewayUrl = trim(gatewayUrl); this.agentExecutor=agentRuntimeExecutor;
    }

    public Dashboard snapshot(Duration window) {
        Instant now = Instant.now();
        var components = new ArrayList<Map<String,Object>>();
        components.add(probe("agentRuntime", "Agent Runtime", runtimeUrl + "/healthz"));
        components.add(probe("gateway", "Runtime Gateway", gatewayUrl + "/alive"));
        components.add(probe("codeGraphWorker", "Code Graph Worker", trim(worker.baseUrl()) + "/internal/code-graph/health"));
        components.add(probe("qdrant", "Qdrant", trim(knowledge.getQdrant().getBaseUrl()) + "/collections"));
        components.add(embeddingStatus(window));
        components.add(component("backend", "Skill Platform Backend", "healthy", 0, null));
        long running = count("select count(*) from agent_run where status in ('PENDING','RUNNING')");
        long pending = count("select count(*) from agent_run where status='PENDING'");
        long runningNow = count("select count(*) from agent_run where status='RUNNING'");
        int executorActive=agentExecutor.getActiveCount();
        int executorQueued=agentExecutor.getQueue().size();
        int executorQueueCapacity=executorQueued+agentExecutor.getQueue().remainingCapacity();
        long dshActive=runtimeMetric(components,"activeRuns");
        long dshMax=runtimeMetric(components,"maxConcurrentRuns");
        long dshBusyRejections=runtimeMetric(components,"busyRejections");
        long minutes=Math.min(1440,Math.max(1,window.toMinutes()));
        long failed = count("select count(*) from agent_run where status='FAILED' and time_created >= DATE_SUB(NOW(), INTERVAL "+minutes+" MINUTE)");
        long total = count("select count(*) from agent_run where status in ('SUCCEEDED','FAILED','CANCELLED') and time_created >= DATE_SUB(NOW(), INTERVAL "+minutes+" MINUTE)");
        double errorRate = total == 0 ? 0 : (failed * 100.0 / total);
        long queuedBuilds = workerMetric(components, "builds", "queued");
        long runningBuilds = workerMetric(components, "builds", "running");
        long runningQueries = workerMetric(components, "queries", "running");
        boolean monitoringReadFailure=false;
        Map<String,Long> binding;
        try{binding=grouped("select semantic_index_status as k, count(*) as v from workflow_run_code_graph_binding group by semantic_index_status");}catch(Exception ex){binding=new LinkedHashMap<>();monitoringReadFailure=true;}
        var qdrant = qdrantState(components);
        var resources = resources();
        Map<String,Object> latencies;
        try{latencies=latencyMetrics();}catch(Exception ex){latencies=Map.of("p95Ms",0,"p99Ms",0,"window","15m","dataStatus","UNAVAILABLE");monitoringReadFailure=true;}
        var alerts = new ArrayList<Map<String,Object>>();
        String overall = components.stream().anyMatch(c -> "critical".equals(c.get("status"))) ? "critical" : "healthy";
        List<Map<String,Object>> persistedTrend=new ArrayList<>();
        try{for(var row:jdbc.queryForList("select bucket_start at,sum(sample_count) total,sum(failure_count) failed from ops_metric_bucket where module='AGENT' and operation='run' and bucket_start>=date_sub(now(3),interval ? minute) group by bucket_start order by bucket_start",minutes)){
            long n=((Number)row.get("total")).longValue(),f=((Number)row.get("failed")).longValue();persistedTrend.add(Map.of("at",row.get("at"),"p95Ms",0,"p99Ms",0,"errorRatePercent",n==0?0:round(f*100.0/n)));
        }}catch(Exception ex){monitoringReadFailure=true;}
        var workload=new LinkedHashMap<String,Object>();
        workload.put("running",running);workload.put("pending",pending);workload.put("runningNow",runningNow);
        workload.put("agentExecutorActive",executorActive);workload.put("agentExecutorQueued",executorQueued);workload.put("agentExecutorQueueCapacity",executorQueueCapacity);
        workload.put("dshActiveRuns",dshActive);workload.put("dshMaxConcurrentRuns",dshMax);workload.put("dshBusyRejections",dshBusyRejections);
        workload.put("recentTotal",total);workload.put("recentFailed",failed);workload.put("errorRatePercent",round(errorRate));
        workload.put("buildRunning",runningBuilds);workload.put("buildQueued",queuedBuilds);workload.put("queryRunning",runningQueries);workload.put("semanticIndexing",binding.getOrDefault("INDEXING", 0L));
        boolean stale=components.stream().anyMatch(c->"unknown".equals(c.get("status")));
        String dataStatus=monitoringReadFailure?"UNAVAILABLE":stale?"PARTIAL":"OK";if((stale||monitoringReadFailure)&&"healthy".equals(overall))overall="unknown";
        Map<String,Object> activeAlerts=new LinkedHashMap<>();
        try{for(var row:jdbc.queryForList("select severity,count(*) n from ops_alert_instance where active_fingerprint is not null group by severity"))activeAlerts.put(String.valueOf(row.get("severity")),row.get("n"));}catch(Exception ex){dataStatus="UNAVAILABLE";}
        try{for(var row:jdbc.queryForList("select id,rule_code,severity,title from ops_alert_instance where active_fingerprint is not null order by id desc limit 20"))alerts.add(alert(String.valueOf(row.get("id")),"P0".equals(row.get("severity"))?"critical":"warning",String.valueOf(row.get("title"))));}catch(Exception ex){dataStatus="UNAVAILABLE";}
        if(alerts.stream().anyMatch(a->"critical".equals(a.get("level"))))overall="critical";else if(!alerts.isEmpty()&&"healthy".equals(overall))overall="warning";
        if("UNAVAILABLE".equals(dataStatus)&&"healthy".equals(overall))overall="unknown";
        return new Dashboard(now, overall, components, workload,
                resources, latencies, binding, qdrant, persistedTrend, alerts,
                Map.of("workerMaxConcurrentBuilds", worker.maxConcurrentBuilds(), "workerMaxConcurrentQueries", worker.workers(), "workerBaseUrl", worker.baseUrl(),"agentMaxConcurrentRuns",agentExecutor.getMaximumPoolSize(),"agentQueueCapacity",executorQueueCapacity),dataStatus,activeAlerts);
    }

    private Map<String,Object> probe(String key,String name,String ignoredUrl){
        try{var rows=jdbc.queryForList("select status,latency_ms,error_code,checked_at from ops_probe_snapshot where component_key=? order by checked_at desc limit 1",key);
            if(rows.isEmpty())return component(key,name,"unknown",0,"暂无探针采样");
            var row=rows.get(0);Object rawChecked=row.get("checked_at");
            java.time.LocalDateTime checked=rawChecked instanceof java.sql.Timestamp timestamp?timestamp.toLocalDateTime():rawChecked instanceof java.time.LocalDateTime local?local:null;
            if(checked==null)throw new IllegalStateException("Unsupported probe time type: "+(rawChecked==null?"null":rawChecked.getClass().getName()));
            if(checked.toInstant(java.time.ZoneOffset.UTC).isBefore(Instant.now().minusSeconds(90)))return component(key,name,"unknown",0,"探针数据已过期");
            return component(key,name,"HEALTHY".equals(row.get("status"))?"healthy":"critical",((Number)row.get("latency_ms")).longValue(),(String)row.get("error_code"));
        }catch(Exception ex){return component(key,name,"unknown",0,"探针数据不可用");}
    }
    private Map<String,Object> embeddingStatus(Duration window) {
        long recentFailures = count("select count(*) from agent_run where error_code like '%QUOTA%' and time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)");
        return component("embedding", "Embedding", recentFailures > 0 ? "degraded" : "unknown", 0, recentFailures > 0 ? "provider_quota_exceeded" : "无主动探测（避免产生费用）");
    }
    private Map<String,Object> component(String key, String name, String status, long latency, String error) {
        var m = new LinkedHashMap<String,Object>(); m.put("key", key); m.put("name", name); m.put("status", status); m.put("latencyMs", latency); if (error != null) m.put("error", error); return m;
    }
    @SuppressWarnings("unchecked") private long workerMetric(List<Map<String,Object>> components, String group, String key) {
        return components.stream().filter(c -> "codeGraphWorker".equals(c.get("key"))).findFirst().map(c -> {
            Object details = c.get("details"); if (!(details instanceof Map<?,?> d)) return 0L; Object g = d.get(group); if (!(g instanceof Map<?,?> m)) return 0L; Object v = m.get(key); return v instanceof Number n ? n.longValue() : 0L;
        }).orElse(0L);
    }
    private long runtimeMetric(List<Map<String,Object>> components,String key){return components.stream().filter(c->"agentRuntime".equals(c.get("key"))).findFirst().map(c->{Object details=c.get("details");if(!(details instanceof Map<?,?> map))return 0L;Object value=map.get(key);return value instanceof Number number?number.longValue():0L;}).orElse(0L);}
    private Map<String,Long> grouped(String sql) { return jdbc.query(sql, rs -> { var result = new LinkedHashMap<String,Long>(); while (rs.next()) result.put(rs.getString("k"), rs.getLong("v")); return result; }); }
    private Map<String,Object> qdrantState(List<Map<String,Object>> components) { var result = new LinkedHashMap<String,Object>(); result.put("collection", knowledge.getQdrant().getCollection()); result.put("reachable", components.stream().anyMatch(c -> "qdrant".equals(c.get("key")) && "healthy".equals(c.get("status")))); return result; }
    private Map<String,Object> resources() { var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(); double cpu = -1; try { cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getCpuLoad() * 100; } catch (Exception ignored) {} long diskUsed = 0, diskTotal = 0; try { FileStore fs = Files.getFileStore(Path.of(".")); diskTotal = fs.getTotalSpace(); diskUsed = diskTotal - fs.getUsableSpace(); } catch (Exception ignored) {} return Map.of("cpuPercent", round(cpu), "memoryUsedBytes", memory.getUsed(), "memoryMaxBytes", memory.getMax(), "diskUsedBytes", diskUsed, "diskTotalBytes", diskTotal); }
    private Map<String,Object> latencyMetrics() { var values = new ArrayList<Long>(jdbc.query("select duration_ms from agent_mcp_audit where duration_ms is not null and time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)", (rs, n) -> rs.getLong(1))); values.sort(Long::compareTo); return Map.of("p95Ms", percentile(values, .95), "p99Ms", percentile(values, .99), "window", "15m","dataStatus",values.isEmpty()?"NO_DATA":"OK"); }
    private long percentile(List<Long> values, double p) { return values.isEmpty() ? 0 : values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * p) - 1)); }
    private void addSignalAlerts(List<Map<String,Object>> alerts) { for (String[] signal : new String[][]{{"quota", "%QUOTA%", "Embedding quota"}, {"429", "%429%", "上游 429"}, {"timeout", "%TIMEOUT%", "调用超时"}, {"fallback", "%FALLBACK%", "Code Graph fallback"}}) { long n = count("select count(*) from agent_run where (error_code like '" + signal[1] + "' or error_message like '" + signal[1] + "') and time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)"); if (n > 0) alerts.add(alert(signal[0], signal[0].equals("quota") ? "critical" : "warning", signal[2] + "（" + n + "）")); } }
    private Map<String,Object> alert(String key, String level, String message) { return Map.of("key", key, "level", level, "message", message); }
    private long count(String sql) { return jdbc.queryForObject(sql,Long.class); }
    private static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
    private static String trim(String v) { return v == null ? "" : v.replaceAll("/$", ""); }
    private static String safe(Exception e) { return Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()).substring(0, Math.min(180, Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()).length())); }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }
    public record Dashboard(Instant generatedAt, String overallStatus, List<Map<String,Object>> components, Map<String,Object> workload, Map<String,Object> resources, Map<String,Object> latency, Map<String,Long> codeGraphBindings, Map<String,Object> qdrant, List<Map<String,Object>> trend, List<Map<String,Object>> alerts, Map<String,Object> capacity,String dataStatus,Map<String,Object> activeAlerts) {}
}
