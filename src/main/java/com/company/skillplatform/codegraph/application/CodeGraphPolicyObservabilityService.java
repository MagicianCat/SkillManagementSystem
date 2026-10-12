package com.company.skillplatform.codegraph.application;

import com.company.skillplatform.common.application.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CodeGraphPolicyObservabilityService {
    private final JdbcTemplate jdbc;private final CodeGraphEvidenceService evidence;
    public CodeGraphPolicyObservabilityService(JdbcTemplate jdbc,CodeGraphEvidenceService evidence){this.jdbc=jdbc;this.evidence=evidence;}
    @Transactional(readOnly=true) public List<Map<String,Object>> usage(long runId,long actor){authorize(runId,actor);return jdbc.queryForList("select i.stage_run_id stageRunId,s.stage_key stageKey,i.node_key nodeKey,sum(not i.preload) toolCallCount,sum(i.preload) preloadQueryCount,sum(i.engine_executed) engineQueryCount,sum(i.cache_hit) cacheHitCount,sum(i.duration_ms) durationMs,sum(i.returned_chars) returnedChars,sum(i.status='BUDGET_EXCEEDED') budgetRejectedCount from code_graph_tool_invocation i join stage_run s on s.id=i.stage_run_id where i.workflow_run_id=? group by i.stage_run_id,s.stage_key,i.node_key order by i.stage_run_id,i.node_key",runId);}
    @Transactional(readOnly=true) public Map<String,Object> evidence(long runId,long actor){authorize(runId,actor);List<Long> binding=jdbc.query("select id from workflow_run_code_graph_binding where workflow_run_id=? and status in ('ACTIVE','SUPERSEDED') order by version_no desc limit 1",(rs,n)->rs.getLong(1),runId);return binding.isEmpty()?Map.of("schemaVersion",1,"facts",List.of()):evidence.latest(runId,binding.get(0));}
    private void authorize(long runId,long actor){Integer count=jdbc.queryForObject("select count(*) from workflow_run w join virtual_project_member m on m.project_id=w.project_id and m.user_id=? and m.status='ACTIVE' where w.id=?",Integer.class,actor,runId);if(count==null||count==0)throw new BusinessException("WORKFLOW_ACCESS_FORBIDDEN","Workflow membership required",HttpStatus.FORBIDDEN);}
}
