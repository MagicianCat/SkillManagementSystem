package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.entity.*;
import com.company.skillplatform.ops.OpsEventService;
import com.company.skillplatform.agent.infrastructure.repository.*;
import com.company.skillplatform.user.infrastructure.entity.IamUserEntity;
import com.company.skillplatform.user.infrastructure.repository.IamUserRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentMcpAuditService {
    private final AgentMcpAuditRepository audits;
    private final AgentRunRepository runs;
    private final IamUserRepository users;private final OpsEventService ops;private final Set<String> criticalTools;

    @org.springframework.beans.factory.annotation.Autowired
    public AgentMcpAuditService(AgentMcpAuditRepository audits, AgentRunRepository runs, IamUserRepository users,OpsEventService ops,
            @org.springframework.beans.factory.annotation.Value("${skill-platform.alerting.critical-mcp-tools:}") String criticalTools) {
        this.audits = audits; this.runs = runs; this.users = users;this.ops=ops;
        this.criticalTools=criticalTools==null||criticalTools.isBlank()?Set.of():Set.of(criticalTools.split(","));
    }
    public AgentMcpAuditService(AgentMcpAuditRepository audits,AgentRunRepository runs,IamUserRepository users){this(audits,runs,users,null,"");}

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void started(String runKey, String callId, String toolName, Map<String, Object> data) {
        if (blank(runKey) || blank(callId) || blank(toolName) || audits.findByRunKeyAndCallId(runKey, callId).isPresent()) return;
        AgentRunEntity run = runs.findByRunKey(runKey).orElse(null);
        if (run == null) return;
        AgentSessionEntity session = run.getSession();
        IamUserEntity actor = session.getOwnerUserId() == null ? null : users.findById(session.getOwnerUserId()).orElse(null);
        AgentMcpAuditEntity audit=new AgentMcpAuditEntity(runKey, session.getSessionKey(), actor, limit(toolName, 128), limit(callId, 128),
                value(session.getSourceChannel(), "WEB"), value(session.getKnowledgeScope(), "USER_VISIBLE"), summarize(data), Instant.now());
        audit.traceId(org.slf4j.MDC.get("traceId"));audits.save(audit);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void completed(String runKey, String callId, boolean success, String errorCode) {
        if (blank(runKey) || blank(callId)) return;
        audits.findByRunKeyAndCallId(runKey, callId).ifPresent(audit -> {
            if (success) audit.succeeded(Instant.now()); else audit.failed(Instant.now(), errorCode);
            audits.save(audit);
            if(ops!=null&&criticalTools.contains(audit.getToolName()))ops.record(new OpsEventService.Event("mcp:"+runKey+":"+callId+":"+(success?"success":"failed"),"agent.mcp.critical_tool","DSH_mcp",audit.getToolName(),success?"SUCCESS":"FAILED",audit.getToolName(),success?null:errorCode,runKey,null,null,null,audit.getDurationMs(),null));
        });
    }

    @PreAuthorize("hasAuthority('admin:audit')")
    @Transactional(readOnly = true)
    public Page<View> list(int page, int size, String runKey, String sessionKey, String toolName, String status,
                           String sourceChannel,String traceId,String errorCode, Instant from, Instant to) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return audits.search(empty(runKey), empty(sessionKey), empty(toolName), empty(status),empty(traceId),empty(errorCode), empty(sourceChannel), from, to, pageable).map(a -> new View(a.getId(), a.getRunKey(), a.getSessionKey(),
                a.getActor() == null ? null : a.getActor().getId(), a.getToolName(), a.getCallId(), a.getSourceChannel(),
                a.getKnowledgeScope(), a.getStatus(), a.getStartedAt(), a.getFinishedAt(), a.getDurationMs(),
                a.getErrorCode(), a.getArgumentsSummary(),a.getTraceId()));
    }

    private static String empty(String value) { return value == null || value.isBlank() ? null : value; }

    private String summarize(Map<String,Object> data){
        // MCP arguments may contain nested credentials, prompts, or document text.
        // Audit only the shape of the call; values remain outside SQL and logs.
        if(data==null)return null;
        Object raw=data.get("arguments");
        if(raw instanceof Map<?,?> map){return "argumentKeys="+map.keySet().stream().map(String::valueOf).filter(k->k.matches("[A-Za-z0-9_]{1,48}")).limit(20).toList();}
        return null;
    }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String limit(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record View(Long id, String runKey, String sessionKey, Long actorUserId, String toolName, String callId,
                       String sourceChannel, String knowledgeScope, String status, Instant startedAt, Instant finishedAt,
                       Long durationMs, String errorCode, String argumentsSummary,String traceId) {}
}
