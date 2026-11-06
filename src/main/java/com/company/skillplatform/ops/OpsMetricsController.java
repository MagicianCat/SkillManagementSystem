package com.company.skillplatform.ops;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/agent/operations")
public class OpsMetricsController {
 private final OpsMetricsService metrics;private final JdbcTemplate db;
 public OpsMetricsController(OpsMetricsService metrics,JdbcTemplate db){this.metrics=metrics;this.db=db;}
 @GetMapping("/metrics") @PreAuthorize("hasAuthority('admin:audit')")
 public Map<String,Object> metrics(@RequestParam String module,@RequestParam(defaultValue="PT1H") String window){return metrics.metrics(module,parseWindow(window));}
 @GetMapping("/traces/{traceId}") @PreAuthorize("hasAuthority('admin:audit')")
 public Map<String,Object> trace(@PathVariable String traceId){if(!traceId.matches("[A-Za-z0-9_-]{1,120}"))throw new IllegalArgumentException("Invalid trace ID");List<Map<String,Object>> items=db.queryForList("select id,event_type,module,operation,outcome,occurred_at,duration_ms,error_code,run_key,project_key,requirement_key from ops_event where trace_id=? order by occurred_at,id limit 200",traceId);return Map.of("traceId",traceId,"dataStatus",items.isEmpty()?"NO_DATA":"OK","events",items);}
 private Duration parseWindow(String value){Duration parsed=Duration.parse(value);if(parsed.isNegative()||parsed.isZero()||parsed.compareTo(Duration.ofDays(1))>0)throw new IllegalArgumentException("Window must be 1m..1d");return parsed;}
}
