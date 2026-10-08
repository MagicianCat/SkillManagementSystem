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

/** Aggregates the operational view used by the admin dashboard. External failures are isolated per component. */
@Service
public class AgentOperationsDashboardService {
    private final RestClient client;
    private final JdbcTemplate jdbc;
    private final CodeGraphWorkerProperties worker;
    private final KnowledgeProperties knowledge;
    private final String runtimeUrl;
    private final String gatewayUrl;
    private final Deque<Map<String,Object>> trend = new ArrayDeque<>();

    public AgentOperationsDashboardService(RestClient.Builder builder, JdbcTemplate jdbc,
            CodeGraphWorkerProperties worker, KnowledgeProperties knowledge,
            @Value("${agent.runtime.base-url:http://127.0.0.1:3090}") String runtimeUrl,
            @Value("${skill-platform.agent-runtime.gateway-url:http://127.0.0.1:18080}") String gatewayUrl) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = builder.clone().requestFactory(factory).build(); this.jdbc = jdbc; this.worker = worker;
        this.knowledge = knowledge; this.runtimeUrl = trim(runtimeUrl); this.gatewayUrl = trim(gatewayUrl);
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
        long failed = count("select count(*) from agent_run where status='FAILED' and time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)");
        long total = count("select count(*) from agent_run where time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)");
        double errorRate = total == 0 ? 0 : (failed * 100.0 / total);
        long queuedBuilds = workerMetric(components, "builds", "queued");
        long runningBuilds = workerMetric(components, "builds", "running");
        long runningQueries = workerMetric(components, "queries", "running");
        var binding = grouped("select semantic_index_status as k, count(*) as v from workflow_run_code_graph_binding group by semantic_index_status");
        var qdrant = qdrantState(components);
        var resources = resources();
        var latencies = latencyMetrics();
        var alerts = new ArrayList<Map<String,Object>>();
        if (errorRate >= 15) alerts.add(alert("agent-error-rate", "critical", "Agent 最近 15 分钟失败率 %.1f%%".formatted(errorRate)));
        else if (errorRate >= 5) alerts.add(alert("agent-error-rate", "warning", "Agent 最近 15 分钟失败率 %.1f%%".formatted(errorRate)));
        addSignalAlerts(alerts);
        components.stream().filter(c -> "critical".equals(c.get("status"))).forEach(c -> alerts.add(alert(String.valueOf(c.get("key")), "critical", c.get("name") + " 不可用")));
        String overall = components.stream().anyMatch(c -> "critical".equals(c.get("status"))) ? "critical" : alerts.stream().anyMatch(a -> "warning".equals(a.get("level"))) ? "warning" : "healthy";
        var point = Map.<String,Object>of("at", now, "p95Ms", latencies.get("p95Ms"), "p99Ms", latencies.get("p99Ms"), "errorRatePercent", round(errorRate));
        synchronized (trend) { trend.addLast(point); while (trend.size() > 180) trend.removeFirst(); }
        return new Dashboard(now, overall, components, Map.of("running", running, "recentTotal", total, "recentFailed", failed, "errorRatePercent", round(errorRate), "buildRunning", runningBuilds, "buildQueued", queuedBuilds, "queryRunning", runningQueries, "semanticIndexing", binding.getOrDefault("INDEXING", 0L)),
                resources, latencies, binding, qdrant, new ArrayList<>(trend), alerts,
                Map.of("workerMaxConcurrentBuilds", worker.maxConcurrentBuilds(), "workerMaxConcurrentQueries", worker.workers(), "workerBaseUrl", worker.baseUrl()));
    }

    private Map<String,Object> probe(String key, String name, String url) {
        long started = System.nanoTime();
        try { var response = client.get().uri(url).retrieve().body(Map.class);
            var result = component(key, name, "healthy", elapsed(started), null); if (response != null) result.put("details", response); return result;
        } catch (Exception e) { return component(key, name, "critical", elapsed(started), safe(e)); }
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
    private Map<String,Long> grouped(String sql) { try { return jdbc.query(sql, rs -> { var result = new LinkedHashMap<String,Long>(); while (rs.next()) result.put(rs.getString("k"), rs.getLong("v")); return result; }); } catch (Exception ignored) { return new LinkedHashMap<>(); } }
    private Map<String,Object> qdrantState(List<Map<String,Object>> components) { var result = new LinkedHashMap<String,Object>(); result.put("collection", knowledge.getQdrant().getCollection()); result.put("reachable", components.stream().anyMatch(c -> "qdrant".equals(c.get("key")) && "healthy".equals(c.get("status")))); return result; }
    private Map<String,Object> resources() { var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage(); double cpu = -1; try { cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getCpuLoad() * 100; } catch (Exception ignored) {} long diskUsed = 0, diskTotal = 0; try { FileStore fs = Files.getFileStore(Path.of(".")); diskTotal = fs.getTotalSpace(); diskUsed = diskTotal - fs.getUsableSpace(); } catch (Exception ignored) {} return Map.of("cpuPercent", round(cpu), "memoryUsedBytes", memory.getUsed(), "memoryMaxBytes", memory.getMax(), "diskUsedBytes", diskUsed, "diskTotalBytes", diskTotal); }
    private Map<String,Object> latencyMetrics() { var values = new ArrayList<Long>(); try { values.addAll(jdbc.query("select duration_ms from agent_mcp_audit where duration_ms is not null and time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)", (rs, n) -> rs.getLong(1))); } catch (Exception ignored) {} values.sort(Long::compareTo); return Map.of("p95Ms", percentile(values, .95), "p99Ms", percentile(values, .99), "window", "15m"); }
    private long percentile(List<Long> values, double p) { return values.isEmpty() ? 0 : values.get(Math.min(values.size() - 1, (int) Math.ceil(values.size() * p) - 1)); }
    private void addSignalAlerts(List<Map<String,Object>> alerts) { for (String[] signal : new String[][]{{"quota", "%QUOTA%", "Embedding quota"}, {"429", "%429%", "上游 429"}, {"timeout", "%TIMEOUT%", "调用超时"}, {"fallback", "%FALLBACK%", "Code Graph fallback"}}) { long n = count("select count(*) from agent_run where (error_code like '" + signal[1] + "' or error_message like '" + signal[1] + "') and time_created >= DATE_SUB(NOW(), INTERVAL 15 MINUTE)"); if (n > 0) alerts.add(alert(signal[0], signal[0].equals("quota") ? "critical" : "warning", signal[2] + "（" + n + "）")); } }
    private Map<String,Object> alert(String key, String level, String message) { return Map.of("key", key, "level", level, "message", message); }
    private long count(String sql) { try { return jdbc.queryForObject(sql, Long.class); } catch (Exception ignored) { return 0; } }
    private static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }
    private static String trim(String v) { return v == null ? "" : v.replaceAll("/$", ""); }
    private static String safe(Exception e) { return Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()).substring(0, Math.min(180, Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName()).length())); }
    private static double round(double v) { return Math.round(v * 10) / 10.0; }
    public record Dashboard(Instant generatedAt, String overallStatus, List<Map<String,Object>> components, Map<String,Object> workload, Map<String,Object> resources, Map<String,Object> latency, Map<String,Long> codeGraphBindings, Map<String,Object> qdrant, List<Map<String,Object>> trend, List<Map<String,Object>> alerts, Map<String,Object> capacity) {}
}
