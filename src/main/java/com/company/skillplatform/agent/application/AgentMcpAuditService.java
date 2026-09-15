package com.company.skillplatform.agent.application;

import com.company.skillplatform.agent.infrastructure.entity.*;
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
    private final IamUserRepository users;

    public AgentMcpAuditService(AgentMcpAuditRepository audits, AgentRunRepository runs, IamUserRepository users) {
        this.audits = audits; this.runs = runs; this.users = users;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void started(String runKey, String callId, String toolName, Map<String, Object> data) {
        if (blank(runKey) || blank(callId) || blank(toolName) || audits.findByRunKeyAndCallId(runKey, callId).isPresent()) return;
        AgentRunEntity run = runs.findByRunKey(runKey).orElse(null);
        if (run == null) return;
        AgentSessionEntity session = run.getSession();
        IamUserEntity actor = session.getOwnerUserId() == null ? null : users.findById(session.getOwnerUserId()).orElse(null);
        audits.save(new AgentMcpAuditEntity(runKey, session.getSessionKey(), actor, limit(toolName, 128), limit(callId, 128),
                value(session.getSourceChannel(), "WEB"), value(session.getKnowledgeScope(), "USER_VISIBLE"), summarize(data), Instant.now()));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void completed(String runKey, String callId, boolean success, String errorCode) {
        if (blank(runKey) || blank(callId)) return;
        audits.findByRunKeyAndCallId(runKey, callId).ifPresent(audit -> {
            if (success) audit.succeeded(Instant.now()); else audit.failed(Instant.now(), errorCode);
            audits.save(audit);
        });
    }

    @PreAuthorize("hasAuthority('admin:audit')")
    @Transactional(readOnly = true)
    public Page<View> list(int page, int size, String runKey, String sessionKey, String toolName, String status,
                           String sourceChannel, Instant from, Instant to) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return audits.search(empty(runKey), empty(sessionKey), empty(toolName), empty(status), empty(sourceChannel), from, to, pageable).map(a -> new View(a.getId(), a.getRunKey(), a.getSessionKey(),
                a.getActor() == null ? null : a.getActor().getId(), a.getToolName(), a.getCallId(), a.getSourceChannel(),
                a.getKnowledgeScope(), a.getStatus(), a.getStartedAt(), a.getFinishedAt(), a.getDurationMs(),
                a.getErrorCode(), a.getArgumentsSummary()));
    }

    private static String empty(String value) { return value == null || value.isBlank() ? null : value; }

    private String summarize(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return null;
        Object args = data.get("argumentsSummary");
        if (args == null) args = data.get("arguments");
        return args == null ? null : limit(String.valueOf(args).replaceAll("(?i)(authorization|token|cookie)=[^,} ]+", "$1=[REDACTED]"), 2000);
    }
    private static String value(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static String limit(String value, int max) { return value == null ? null : value.substring(0, Math.min(value.length(), max)); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    public record View(Long id, String runKey, String sessionKey, Long actorUserId, String toolName, String callId,
                       String sourceChannel, String knowledgeScope, String status, Instant startedAt, Instant finishedAt,
                       Long durationMs, String errorCode, String argumentsSummary) {}
}
